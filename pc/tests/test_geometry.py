import math

import pytest

from regua_pc.geometry import PathEnd, PocketId, Spin, Table, Vec2, solve, trace

TABLE = Table(0.0, 0.0, 400.0, 200.0, 10.0)


def close(a: Vec2, b: Vec2, tol: float = 1e-6) -> bool:
    return abs(a.x - b.x) <= tol and abs(a.y - b.y) <= tol


def test_straight_line_stops_at_first_cushion():
    path = trace(TABLE, Vec2(100, 80), Vec2(1, 0), 0)
    assert path.end is PathEnd.CUSHION
    assert close(path.points[-1], Vec2(390, 80))


def test_bounce_keeps_angle():
    path = trace(TABLE, Vec2(100, 120), Vec2(1, 1), 1)
    assert close(path.bounces[0], Vec2(170, 190))
    assert close(path.direction, Vec2(1, -1).normalized())
    assert close(path.points[-1], Vec2(350, 10))


def test_corner_and_side_pockets():
    assert trace(TABLE, Vec2(200, 100), Vec2(-190, -90), 3).pocket.id is PocketId.TOP_LEFT
    assert trace(TABLE, Vec2(200, 150), Vec2(0, -1), 3).pocket.id is PocketId.TOP_MIDDLE
    shallow = trace(TABLE, Vec2(100, 30), Vec2(1, -0.2), 1)
    assert close(shallow.bounces[0], Vec2(200, 10))
    assert shallow.pocket is None or shallow.pocket.id is not PocketId.TOP_MIDDLE


def test_full_hit_and_cut():
    full = solve(TABLE, Vec2(100, 100), Vec2(1, 0), Vec2(200, 100), 2)
    assert close(full.ghost, Vec2(180, 100))
    assert full.cue_after is None
    cut = solve(TABLE, Vec2(50, 90), Vec2(1, 0), Vec2(200, 100), 1)
    assert cut.cut_angle == pytest.approx(30.0)
    o = cut.object_ball.points
    c = cut.cue_after.points
    od = (o[1] - o[0]).normalized()
    cd = (c[1] - c[0]).normalized()
    assert abs(od.dot(cd)) < 1e-6


def test_spin():
    draw = solve(TABLE, Vec2(100, 100), Vec2(1, 0), Vec2(200, 100), 1, Spin(follow=-1))
    assert draw.cue_after.direction.x < 0
    right = trace(TABLE, Vec2(100, 150), Vec2(0, -1), 1, side_spin=1.0)
    assert right.direction.x > 0.2 and right.direction.y > 0


def test_obstacles_and_combo():
    shot = solve(TABLE, Vec2(50, 100), Vec2(1, 0), Vec2(300, 100), 2, obstacles=[Vec2(150, 100)])
    assert shot.cue.hit_ball == Vec2(150, 100)
    assert shot.object_ball.end is PathEnd.BALL
    assert close(shot.combo.points[-1], Vec2(390, 100))


def test_matches_android_numbers():
    # Mesmo caso do teste Kotlin: bola fantasma a 2 raios do alvo, ângulo de corte de 30°.
    shot = solve(TABLE, Vec2(50, 90), Vec2(1, 0), Vec2(200, 100), 1)
    assert shot.ghost.distance_to(Vec2(200, 100)) == pytest.approx(20.0)
    assert math.isclose(shot.cut_angle, 30.0, abs_tol=1e-6)
