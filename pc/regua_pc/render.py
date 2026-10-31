"""Desenha a rota da bola com QPainter (mesmas cores e regras do app Android).

O que a régua desenha nunca é branco e deixa livres a branca, a linha do jogo, o anel e a linha da
bola alvo: se algum dia a captura pegar a própria régua, ela não se confunde com as linhas do jogo.
"""

from __future__ import annotations

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import QColor, QPainter, QPainterPath, QPen, QPolygonF

from .geometry import BallPath, PathEnd, Spin, Table, Vec2, solve
from .settings import Settings
from .vision import AimResult

YELLOW = QColor(0xFF, 0xEB, 0x3B)
CYAN = QColor(0x18, 0xFF, 0xFF)
GREEN = QColor(0x00, 0xE6, 0x76)
RED = QColor(0xFF, 0x52, 0x52)
LILAC = QColor(0xE0, 0x40, 0xFB)
SHADOW = QColor(0, 0, 0, 0x99)


def draw_auto_shot(p: QPainter, aim: AimResult, table: Table, settings: Settings) -> None:
    r = table.ball_radius
    obstacles = aim.balls if settings.use_balls else []
    shot = solve(table, aim.cue, aim.direction, aim.object_ball, settings.cushions,
                 Spin(settings.spin_side, settings.spin_follow), obstacles, cushion=aim.cushion)
    width = max(2.0, 0.2 * r)

    holes = QPainterPath()
    holes.setFillRule(Qt.WindingFill)
    _circle(holes, aim.cue, 1.6 * r)
    _band(holes, aim.cue, aim.cue + aim.direction * aim.guide_length, 0.5 * r)
    if aim.ghost is not None:
        _circle(holes, aim.ghost, 3 * r)
        if aim.object_ball is not None:
            n = (aim.object_ball - aim.ghost).normalized()
            _band(holes, aim.object_ball, aim.object_ball + n * aim.object_guide_length, 0.5 * r)
    area = QPainterPath()
    area.addRect(QRectF(-1e5, -1e5, 2e5, 2e5))
    p.save()
    p.setClipPath(area.subtracted(holes))

    cue_color = RED if shot.cue.end is PathEnd.POCKET else YELLOW
    _path(p, shot.cue, cue_color, width, skip=aim.guide_length + 0.6 * r)
    _contact(p, shot.cue, cue_color, width, r)
    for path, skip in ((shot.object_ball, max(1.5 * r, aim.object_guide_length + 0.6 * r)), (shot.combo, r)):
        if path is not None:
            color = GREEN if path.end is PathEnd.POCKET else CYAN
            _path(p, path, color, width * (1.25 if color is GREEN else 1.0), skip)
            _contact(p, path, color, width, r)
    if settings.show_cue_after and shot.cue_after is not None:
        color = RED if shot.cue_after.end is PathEnd.POCKET else LILAC
        _path(p, shot.cue_after, color, width * 0.75, skip=r, dashed=True)
        _contact(p, shot.cue_after, color, width, r)
    p.restore()


def draw_table(p: QPainter, table: Table, editing: bool = False) -> None:
    """Retângulo das tabelas e caçapas, para conferir a calibração."""
    r = table.ball_radius
    w = max(1.5, 0.08 * r)
    p.setBrush(Qt.NoBrush)
    p.setPen(QPen(CYAN, w))
    p.drawRect(QRectF(table.left, table.top, table.width, table.height))
    dashed = QPen(QColor(255, 255, 255, 200), w)
    dashed.setDashPattern([3, 3])
    p.setPen(dashed)
    for pocket in table.pockets:
        p.drawEllipse(QPointF(pocket.center.x, pocket.center.y), pocket.radius, pocket.radius)
    if editing:
        p.setPen(QPen(QColor(20, 20, 20), w))
        p.setBrush(QColor(255, 255, 255))
        for x, y in ((table.left, table.top), (table.right, table.top), (table.left, table.bottom), (table.right, table.bottom)):
            p.drawEllipse(QPointF(x, y), 2.2 * w + 3, 2.2 * w + 3)


def _circle(path: QPainterPath, c: Vec2, radius: float) -> None:
    path.addEllipse(QPointF(c.x, c.y), radius, radius)


def _band(path: QPainterPath, a: Vec2, b: Vec2, half: float) -> None:
    u = (b - a).normalized()
    if u.length == 0:
        return
    n = Vec2(-u.y, u.x) * half
    start, end = a - u * half, b + u * half
    poly = QPolygonF([QPointF(q.x, q.y) for q in (start - n, end - n, end + n, start + n)])
    sub = QPainterPath()
    sub.addPolygon(poly)
    sub.closeSubpath()
    path.addPath(sub)


def _stroke(p: QPainter, draw, color: QColor, width: float, dashed: bool = False) -> None:
    """Desenha com um contorno escuro por baixo, para aparecer em mesa de qualquer cor."""
    for c, w in ((SHADOW, width + 2.5), (color, width)):
        pen = QPen(c, w)
        pen.setCapStyle(Qt.RoundCap)
        pen.setJoinStyle(Qt.RoundJoin)
        if dashed:
            pen.setDashPattern([2.5 * width / w * 2, 2.0 * width / w * 2])
        p.setPen(pen)
        p.setBrush(Qt.NoBrush)
        draw()


def _path(p: QPainter, ball: BallPath, color: QColor, width: float, skip: float = 0.0, dashed: bool = False) -> None:
    path = QPainterPath()
    travelled = 0.0
    started = False
    for a, b in zip(ball.points, ball.points[1:]):
        length = a.distance_to(b)
        if length == 0:
            continue
        if travelled + length > skip:
            if not started:
                t = min(max((skip - travelled) / length, 0.0), 1.0)
                s = a + (b - a) * t
                path.moveTo(s.x, s.y)
                started = True
            path.lineTo(b.x, b.y)
        travelled += length
    if started:
        _stroke(p, lambda: p.drawPath(path), color, width, dashed)
    # Pontos de batida nas tabelas, só no trecho desenhado.
    travelled = 0.0
    p.setPen(Qt.NoPen)
    p.setBrush(color)
    for a, b in zip(ball.points, ball.points[1:]):
        travelled += a.distance_to(b)
        if travelled > skip and b in ball.bounces:
            p.drawEllipse(QPointF(b.x, b.y), width * 1.4, width * 1.4)
    if ball.pocket is not None:
        c = ball.pocket.center
        _stroke(p, lambda: p.drawEllipse(QPointF(c.x, c.y), ball.pocket.radius, ball.pocket.radius), color, width * 1.4)


def _contact(p: QPainter, ball: BallPath, color: QColor, width: float, r: float) -> None:
    """Se o caminho termina encostando numa bola ou numa ponta da boca da caçapa, marca onde a bola
    em movimento fica no choque."""
    if ball.end not in (PathEnd.BALL, PathEnd.JAW):
        return
    c = ball.points[-1]
    _stroke(p, lambda: p.drawEllipse(QPointF(c.x, c.y), r, r), color, width * 0.75, dashed=True)
