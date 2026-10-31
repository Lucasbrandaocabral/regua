import math

import numpy as np
import pytest

from regua_pc.balls import BallFinder
from regua_pc.calibration import find_table
from regua_pc.geometry import Table, Vec2, solve
from regua_pc.vision import AimDetector

from .synthetic import SyntheticTable


def angle_between(a: Vec2, b: Vec2) -> float:
    return math.degrees(math.acos(max(-1.0, min(1.0, a.normalized().dot(b.normalized())))))


@pytest.mark.parametrize("degrees", [0.0, 17.3, 123.4, 251.7, 333.3])
def test_follows_guide(degrees):
    scene = SyntheticTable()
    cue = Vec2(600, 280)
    d = Vec2.of_angle(math.radians(degrees))
    scene.ball(Vec2(250, 120), (220, 30, 30))
    scene.aim_at(cue, None, d)
    res = AimDetector().detect(scene.frame(), scene.table)
    assert res is not None
    assert angle_between(res.direction, d) < 0.15
    assert res.cue.distance_to(cue) < 3.0
    assert res.ghost is None


@pytest.mark.parametrize("degrees,offset", [(0.0, 0.0), (10.0, 9.0), (200.0, -14.0), (300.0, 20.0)])
def test_ghost_and_object_ball(degrees, offset):
    scene = SyntheticTable()
    cue = Vec2(600, 280)
    d = Vec2.of_angle(math.radians(degrees))
    target = cue + d * (8 * scene.r) + Vec2(-d.y, d.x) * offset
    scene.ball(target, (40, 150, 60))
    scene.aim_at(cue, target, d)
    res = AimDetector().detect(scene.frame(), scene.table)
    assert angle_between(res.direction, d) < 0.15
    assert res.ghost is not None and res.ghost.distance_to(target) == pytest.approx(2 * scene.r, abs=1.5)
    m = cue - target
    b = m.dot(d)
    true_ghost = cue + d * (-b - math.sqrt(b * b - (m.dot(m) - 4 * scene.r ** 2)))
    expected = (target - true_ghost).normalized()
    assert angle_between(res.object_ball - res.ghost, expected) < 0.3


@pytest.mark.parametrize("degrees", [7.0, 27.0, 153.0, 211.0, 333.0])
def test_object_direction_on_thin_cut(degrees):
    # Corte fino na mesa do tamanho do Google Play Games: o jogo desenha só um pedacinho da linha da
    # bola alvo, e meio grau de erro ali vira uns 4 px a cada 500 px de caminho.
    t = Table(196.3, 214.1, 944.7, 588.2, 11.3)
    scene = SyntheticTable(1074, 672, t)
    cue = Vec2(560, 400)
    d = Vec2.of_angle(math.radians(degrees))
    target = cue + d * (9 * scene.r) + Vec2(-d.y, d.x) * (0.92 * 2 * scene.r)
    scene.ball(target, (40, 150, 60))
    scene.aim_at(cue, target, d, object_line=2.5)
    res = AimDetector().detect(scene.frame(), t)
    m = cue - target
    b = m.dot(d)
    true_ghost = cue + d * (-b - math.sqrt(b * b - (m.dot(m) - 4 * scene.r ** 2)))
    assert angle_between(res.object_ball - res.ghost, target - true_ghost) < 0.15


@pytest.mark.parametrize("distance,degrees,cut", [
    (2.5, 222.0, -40.0),  # como no print: anel a 2,5 raios da branca, mira na diagonal
    (2.5, 30.0, 40.0),
    (2.8, 131.0, -15.0),
    (3.2, 0.0, 60.0),
    (3.2, 300.0, -60.0),
])
def test_ghost_close_to_cue(distance, degrees, cut):
    # Anel quase encostado na branca: o traço da mira entre as duas tem menos de um raio, e um raio
    # que cruza o anel e a linha da tangente vai mais longe que ele.
    t = Table(196.3, 214.1, 944.7, 588.2, 11.3)
    scene = SyntheticTable(1074, 672, t)
    cue = Vec2(573.8, 402.9)
    d = Vec2.of_angle(math.radians(degrees))
    ghost = cue + d * (distance * scene.r)
    target = ghost + Vec2.of_angle(math.radians(degrees + cut)) * (2 * scene.r)
    scene.ball(target, (230, 140, 30))
    scene.aim_at(cue, target, d, object_line=4.0)
    res = AimDetector().detect(scene.frame(), t)
    assert res is not None and res.ghost is not None
    assert res.ghost.distance_to(ghost) < 1.0
    assert angle_between(res.direction, d) < 1.0
    assert angle_between(res.object_ball - res.ghost, target - ghost) < 0.3


@pytest.mark.parametrize("distance,degrees,cut", [
    (0.6, 40.0, 30.0), (0.9, 200.0, -45.0), (1.3, 300.0, 60.0), (1.8, 115.0, -20.0), (1.1, 0.0, 0.0),
])
def test_ring_over_the_cue_ball(distance, degrees, cut):
    # Branca quase encostada na bola alvo: o anel fica por cima da própria branca e cobre o miolo dela.
    scene = SyntheticTable()
    cue = Vec2(603.3, 281.7)
    d = Vec2.of_angle(math.radians(degrees))
    ghost = cue + d * (distance * scene.r)
    target = ghost + Vec2.of_angle(math.radians(degrees + cut)) * (2 * scene.r)
    scene.ball(target, (120, 30, 20), striped=True)
    scene.aim_at(cue, target, d)
    res = AimDetector().detect(scene.frame(), scene.table)
    assert res is not None and res.ghost is not None
    assert res.cue.distance_to(cue) < 2.0
    assert res.ghost.distance_to(ghost) < 1.5
    assert angle_between(res.object_ball - res.ghost, target - ghost) < 1.5


def test_ring_on_cushion_with_ball_is_a_target():
    scene = SyntheticTable()
    cue = Vec2(500, 300)
    target = Vec2(800, scene.table.max_y - 2)
    d = (target - Vec2(0, 0.8 * 2 * scene.r) - cue).normalized()
    scene.ball(target, (40, 150, 60))
    scene.aim_at(cue, target, d)
    res = AimDetector().detect(scene.frame(), scene.table)
    assert res is not None and res.ghost is not None
    assert res.object_ball.distance_to(target) < 3.0


def test_simulated_game_scenes():
    # Mesas parecidas com as do jogo (simulador.py), duas de cada tipo de tacada.
    from tests.game_scene import KINDS, TABLE, random_scene
    import simulador
    ok = 0
    for i in range(10):
        rgb, truth = random_scene(4242 + i, KINDS[i % len(KINDS)])
        ok += simulador.check(AimDetector().detect(rgb, TABLE), truth) is None
    assert ok >= 9


@pytest.mark.parametrize("degrees", [250.0, 297.0])
def test_cushion_ring_gives_the_bounce_point(degrees):
    # Mira direta na tabela de cima, com a linha de verdade 2,5 px para dentro da calibração.
    scene = SyntheticTable()
    t = scene.table
    cue = Vec2(600, 280)
    d = Vec2.of_angle(math.radians(degrees))
    contact = cue + d * ((cue.y - (t.min_y + 2.5)) / -d.y)
    scene.cue(cue, d)
    scene.cue_ball(cue)
    scene.guide(cue, contact)
    scene.ring(contact)
    res = AimDetector().detect(scene.frame(), t)
    assert res is not None and res.ghost is None
    assert res.cushion is not None and res.cushion.distance_to(contact) < 0.7
    shot = solve(t, res.cue, res.direction, None, 1, cushion=res.cushion)
    assert shot.cue.bounces[0].distance_to(contact) < 0.7


def test_previous_frame_hint_and_fallback():
    cue = Vec2(600, 280)
    before = SyntheticTable()
    before.aim_at(cue, None, Vec2.of_angle(math.radians(40)))
    detector = AimDetector()
    prev = detector.detect(before.frame(), before.table)
    after = SyntheticTable()
    d = Vec2.of_angle(math.radians(46))
    after.aim_at(cue, None, d)
    assert angle_between(detector.detect(after.frame(), after.table, prev).direction, d) < 0.15
    moved = SyntheticTable()
    moved.aim_at(Vec2(300, 400), None, Vec2(-1, 0))
    res = detector.detect(moved.frame(), moved.table, prev)
    assert res.cue.distance_to(Vec2(300, 400)) < 3.0


def test_cue_ball_next_to_whiter_stripe_ball():
    # A listrada com o lado branco para cima tem mais branco que a branca coberta pela linha de mira.
    scene = SyntheticTable()
    scene.stripe_ball_white_up(Vec2(800, 150), (200, 20, 20))
    cue = Vec2(600, 280)
    d = Vec2.of_angle(math.radians(200))
    target = cue + d * (8 * scene.r)
    scene.ball(target, (40, 150, 60))
    scene.aim_at(cue, target, d)
    res = AimDetector().detect(scene.frame(), scene.table)
    assert res is not None and res.cue.distance_to(cue) < 3.0
    assert res.ghost is not None


def test_nothing_when_table_is_not_on_screen():
    # Como no menu do jogo: sem as bordas das tabelas, texto branco não pode virar branca e mira.
    scene = SyntheticTable()
    cue = Vec2(600, 280)
    scene.aim_at(cue, None, Vec2(1, 0))
    menu = scene.frame().copy()
    t = scene.table
    yy, xx = np.mgrid[0:menu.shape[0], 0:menu.shape[1]]
    inside = (xx >= t.left - 40) & (xx <= t.right + 40) & (yy >= t.top - 40) & (yy <= t.bottom + 40)
    on_aim = (np.abs(yy - cue.y) < 20) & (xx > cue.x - 30)
    menu[inside & ~on_aim] = (120, 40, 160)
    assert AimDetector().detect(menu, t) is None
    assert AimDetector().detect(scene.frame(), t) is not None


def test_nothing_without_guide():
    scene = SyntheticTable()
    scene.cue_ball(Vec2(500, 250))
    assert AimDetector().detect(scene.frame(), scene.table) is None


def test_ball_finder_and_other_balls():
    scene = SyntheticTable()
    balls = [Vec2(250, 120), Vec2(900, 430), Vec2(700, 100), Vec2(400, 420), Vec2(430, 420), Vec2(1000, 200)]
    colors = [(220, 30, 30), (240, 200, 20), (20, 20, 20), (230, 120, 20), (120, 30, 140), (30, 80, 190)]
    for b, c in zip(balls, colors):
        scene.ball(b, c)
    cue = Vec2(600, 280)
    scene.aim_at(cue, None, Vec2.of_angle(math.radians(200)))
    found = BallFinder().find(scene.frame(), scene.table)
    for b in balls + [cue]:
        assert min(f.distance_to(b) for f in found) < 2.0
    assert len(found) == len(balls) + 1
    res = AimDetector().detect(scene.frame(), scene.table)
    assert len(res.balls) == len(balls)


@pytest.mark.parametrize("width,height,t", [
    # Janela 16:9 com a mesa uns pixels fora do layout padrão do celular.
    (1600, 900, Table(196.0, 216.0, 1404.0, 818.0, 18.2)),
    # Google Play Games: janela mais quadrada e com barra lateral, mesa menor e fora do centro.
    (1074, 672, Table(196.3, 214.1, 944.7, 588.2, 11.3)),
])
@pytest.mark.parametrize("decorated", [False, True])
def test_auto_calibration_finds_cushions(width, height, t, decorated):
    # Com trilho enfeitado (mesas de torneio), a borda do trilho é mais forte que a da borracha e
    # também forma 2:1; a bola bate na de dentro.
    scene = SyntheticTable(width, height, t)
    if decorated:
        scene.decorated_rails()
    found = find_table(scene.frame())
    assert found is not None
    for a, b in ((found.left, t.left), (found.top, t.top), (found.right, t.right), (found.bottom, t.bottom)):
        assert a == pytest.approx(b, abs=1.5)
