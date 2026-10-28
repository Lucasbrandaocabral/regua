import math

import pytest

from regua_pc.balls import BallFinder
from regua_pc.calibration import find_table
from regua_pc.geometry import Table, Vec2
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


def test_auto_calibration_finds_cushions():
    # Janela 16:9 com a mesa uns pixels fora do layout padrão (o chute inicial).
    t = Table(196.0, 216.0, 1404.0, 818.0, 18.2)
    scene = SyntheticTable(1600, 900, t)
    found = find_table(scene.frame())
    assert found is not None
    for a, b in ((found.left, t.left), (found.top, t.top), (found.right, t.right), (found.bottom, t.bottom)):
        assert a == pytest.approx(b, abs=1.5)
