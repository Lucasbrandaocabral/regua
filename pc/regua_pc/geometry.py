"""Física da régua: reflexão nas tabelas, caçapas, bola fantasma e efeito.

Porte direto de ``geometry/ShotSolver.kt`` do app Android; as coordenadas são pixels da tela
(y cresce para baixo).
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from enum import Enum


@dataclass(frozen=True)
class Vec2:
    x: float
    y: float

    def __add__(self, o: "Vec2") -> "Vec2":
        return Vec2(self.x + o.x, self.y + o.y)

    def __sub__(self, o: "Vec2") -> "Vec2":
        return Vec2(self.x - o.x, self.y - o.y)

    def __mul__(self, k: float) -> "Vec2":
        return Vec2(self.x * k, self.y * k)

    def __neg__(self) -> "Vec2":
        return Vec2(-self.x, -self.y)

    def dot(self, o: "Vec2") -> float:
        return self.x * o.x + self.y * o.y

    @property
    def length(self) -> float:
        return math.hypot(self.x, self.y)

    def distance_to(self, o: "Vec2") -> float:
        return math.hypot(self.x - o.x, self.y - o.y)

    def normalized(self) -> "Vec2":
        n = self.length
        return ZERO if n < 1e-9 else Vec2(self.x / n, self.y / n)

    @staticmethod
    def of_angle(radians: float) -> "Vec2":
        return Vec2(math.cos(radians), math.sin(radians))


ZERO = Vec2(0.0, 0.0)


class PocketId(Enum):
    TOP_LEFT = "top_left"
    TOP_MIDDLE = "top_middle"
    TOP_RIGHT = "top_right"
    BOTTOM_LEFT = "bottom_left"
    BOTTOM_MIDDLE = "bottom_middle"
    BOTTOM_RIGHT = "bottom_right"


@dataclass(frozen=True)
class Pocket:
    """Buraco da caçapa: só serve para desenhar; quem decide se a bola entra é o solver."""

    id: PocketId
    center: Vec2
    radius: float


# Medidas em raios de bola, tiradas de prints do 8 Ball Pool (todas as mesas têm a mesma geometria).
# As pontas da boca são onde a borracha reta acaba, na linha das tabelas.
CORNER_JAW = 2.2  # ponta da caçapa de canto, contando da tabela do lado
SIDE_JAW = 1.9  # ponta da caçapa do meio, contando do meio da tabela
JAW_CLEARANCE = 0.95  # a bola só cai se o centro passa pelo menos isso longe das duas pontas
CORNER_HOLE_OFFSET = 1.0
CORNER_HOLE_RADIUS = 2.1
SIDE_HOLE_OFFSET = 1.8
SIDE_HOLE_RADIUS = 1.7


@dataclass(frozen=True)
class Table:
    """Mesa calibrada em pixels da tela.

    ``left``/``top``/``right``/``bottom`` são as linhas onde a bola encosta nas tabelas; o centro da
    bola anda no retângulo interno, um raio para dentro.
    """

    left: float
    top: float
    right: float
    bottom: float
    ball_radius: float

    @property
    def width(self) -> float:
        return self.right - self.left

    @property
    def height(self) -> float:
        return self.bottom - self.top

    @property
    def center_x(self) -> float:
        return (self.left + self.right) / 2

    @property
    def min_x(self) -> float:
        return self.left + self.ball_radius

    @property
    def max_x(self) -> float:
        return self.right - self.ball_radius

    @property
    def min_y(self) -> float:
        return self.top + self.ball_radius

    @property
    def max_y(self) -> float:
        return self.bottom - self.ball_radius

    def pocket(self, pid: PocketId) -> Pocket:
        r = self.ball_radius
        co, cr = CORNER_HOLE_OFFSET * r, CORNER_HOLE_RADIUS * r
        if pid is PocketId.TOP_LEFT:
            return Pocket(pid, Vec2(self.left - co, self.top - co), cr)
        if pid is PocketId.TOP_RIGHT:
            return Pocket(pid, Vec2(self.right + co, self.top - co), cr)
        if pid is PocketId.BOTTOM_LEFT:
            return Pocket(pid, Vec2(self.left - co, self.bottom + co), cr)
        if pid is PocketId.BOTTOM_RIGHT:
            return Pocket(pid, Vec2(self.right + co, self.bottom + co), cr)
        if pid is PocketId.TOP_MIDDLE:
            return Pocket(pid, Vec2(self.center_x, self.top - SIDE_HOLE_OFFSET * r), SIDE_HOLE_RADIUS * r)
        return Pocket(pid, Vec2(self.center_x, self.bottom + SIDE_HOLE_OFFSET * r), SIDE_HOLE_RADIUS * r)

    def mouth(self, pid: PocketId) -> tuple[Vec2, Vec2]:
        """As duas pontas da boca da caçapa."""
        j, s = CORNER_JAW * self.ball_radius, SIDE_JAW * self.ball_radius
        if pid is PocketId.TOP_LEFT:
            return Vec2(self.left + j, self.top), Vec2(self.left, self.top + j)
        if pid is PocketId.TOP_RIGHT:
            return Vec2(self.right - j, self.top), Vec2(self.right, self.top + j)
        if pid is PocketId.BOTTOM_LEFT:
            return Vec2(self.left + j, self.bottom), Vec2(self.left, self.bottom - j)
        if pid is PocketId.BOTTOM_RIGHT:
            return Vec2(self.right - j, self.bottom), Vec2(self.right, self.bottom - j)
        y = self.top if pid is PocketId.TOP_MIDDLE else self.bottom
        return Vec2(self.center_x - s, y), Vec2(self.center_x + s, y)

    @property
    def pockets(self) -> list[Pocket]:
        return [self.pocket(p) for p in PocketId]


class PathEnd(Enum):
    CUSHION = "cushion"  # parou na última tabela permitida
    POCKET = "pocket"  # entrou numa caçapa
    JAW = "jaw"  # chegou na boca da caçapa mas bate numa das pontas
    BALL = "ball"  # encostou em outra bola
    LIMIT = "limit"  # acabou o comprimento máximo


@dataclass
class BallPath:
    points: list[Vec2]  # polilinha do centro da bola: início, batidas e fim
    bounces: list[Vec2]  # só os pontos onde bateu numa tabela
    end: PathEnd
    direction: Vec2  # direção no fim do caminho
    pocket: Pocket | None = None
    hit_ball: Vec2 | None = None  # centro da bola atingida, quando end é BALL


@dataclass(frozen=True)
class Spin:
    """Efeito na branca como se marca no jogo (-1 a 1): side > 0 à direita, follow > 0 em cima."""

    side: float = 0.0
    follow: float = 0.0


@dataclass
class Shot:
    cue: BallPath
    object_ball: BallPath | None = None  # caminho da bola alvo depois do choque
    cue_after: BallPath | None = None  # caminho da branca depois do choque
    cut_angle: float | None = None  # graus: 0 é bola cheia
    combo: BallPath | None = None  # bola que a bola alvo acerta em seguida

    @property
    def ghost(self) -> Vec2 | None:
        return self.cue.points[-1] if self.cue.end is PathEnd.BALL else None


_EPS = 1e-9
_MIN_CUE_TRAVEL = 0.03
_FOLLOW_FACTOR = 0.4
_SIDE_SPIN_KICK = 0.35
_SIDE_SPIN_DECAY = 0.5


def solve(
    table: Table,
    cue: Vec2,
    aim: Vec2,
    target: Vec2 | None,
    cushions: int,
    spin: Spin = Spin(),
    obstacles: list[Vec2] | tuple[Vec2, ...] = (),
) -> Shot:
    """Calcula a tacada. A primeira bola que a branca encostar (o alvo ou um dos obstáculos) vira a
    bola alvo; os caminhos param na bola que encostarem e, se a bola alvo acertar outra, calcula
    também o caminho dessa outra (combinação).

    ``target`` é a bola que o anel do jogo encosta: se a branca chega nela em linha reta, nenhum
    obstáculo para a branca antes. O jogo já mostrou que ela passa; uma bola que pareça estar no
    caminho só raspa por um ou dois pixels de erro na posição achada."""
    balls = ([target] if target is not None else []) + list(obstacles)
    cue_path = None
    if target is not None:
        cue_path = trace(table, cue, aim, cushions, [target], side_spin=spin.side)
        if cue_path.hit_ball is None or cue_path.bounces:
            cue_path = None
    if cue_path is None:
        cue_path = trace(table, cue, aim, cushions, balls, side_spin=spin.side)
    hit = cue_path.hit_ball
    if hit is None:
        return Shot(cue_path)
    others = [b for b in balls if b != hit]
    ghost = cue_path.points[-1]
    incoming = cue_path.direction
    object_dir = (hit - ghost).normalized()
    cos_cut = max(-1.0, min(1.0, incoming.dot(object_dir)))
    object_path = trace(table, hit, object_dir, cushions, others)
    combo = None
    if object_path.hit_ball is not None:
        second = object_path.hit_ball
        contact = object_path.points[-1]
        combo = trace(table, second, (second - contact).normalized(), cushions, [b for b in others if b != second])
    # Sem efeito a branca sai a 90° da bola alvo; efeito em cima/embaixo soma na direção dela.
    tangent = incoming - object_dir * cos_cut
    cue_out = tangent + object_dir * (cos_cut * _FOLLOW_FACTOR * spin.follow)
    travel = cue_out.length
    cue_after = None
    if travel > _MIN_CUE_TRAVEL:
        cue_after = trace(
            table, ghost, cue_out, min(cushions, 2), others,
            side_spin=spin.side, max_length=table.width * min(1.0, travel),
        )
    return Shot(cue_path, object_path, cue_after, math.degrees(math.acos(cos_cut)), combo)


def trace(
    table: Table,
    start: Vec2,
    direction: Vec2,
    max_cushions: int,
    balls: list[Vec2] | tuple = (),
    side_spin: float = 0.0,
    max_length: float = math.inf,
) -> BallPath:
    """Segue o centro de uma bola refletindo nas tabelas até ``max_cushions`` vezes; para antes se
    entrar numa caçapa, encostar numa das ``balls`` ou andar ``max_length`` pixels."""
    pos = Vec2(min(max(start.x, table.min_x), table.max_x), min(max(start.y, table.min_y), table.max_y))
    d = direction.normalized()
    points = [pos]
    bounces: list[Vec2] = []
    if d == ZERO:
        return BallPath(points, bounces, PathEnd.LIMIT, d)
    remaining = max_length
    spin = side_spin
    while True:
        if d.x > _EPS:
            tx = (table.max_x - pos.x) / d.x
        elif d.x < -_EPS:
            tx = (table.min_x - pos.x) / d.x
        else:
            tx = math.inf
        if d.y > _EPS:
            ty = (table.max_y - pos.y) / d.y
        elif d.y < -_EPS:
            ty = (table.min_y - pos.y) / d.y
        else:
            ty = math.inf
        t_wall = min(tx, ty)

        hit = _first_ball_hit(table, pos, d, balls, min(t_wall, remaining))
        if hit is not None:
            points.append(pos + d * hit[0])
            return BallPath(points, bounces, PathEnd.BALL, d, hit_ball=hit[1])
        if remaining <= t_wall:
            points.append(pos + d * remaining)
            return BallPath(points, bounces, PathEnd.LIMIT, d)

        vertical = tx <= ty + _EPS  # bate na tabela da esquerda/direita
        horizontal = ty <= tx + _EPS  # bate na de cima/baixo
        raw = pos + d * t_wall
        wall = Vec2(
            (table.max_x if d.x > 0 else table.min_x) if vertical else raw.x,
            (table.max_y if d.y > 0 else table.min_y) if horizontal else raw.y,
        )
        points.append(wall)

        pocket = _mouth_at(table, wall, d, vertical, horizontal)
        if pocket is not None:
            if not _drops(table, pocket, pos, d):
                return BallPath(points, bounces, PathEnd.JAW, d)
            # Continua um pouco para dentro do buraco, só para a linha terminar na caçapa.
            depth = min(max((pocket.center - wall).dot(d), 0.0), 3 * table.ball_radius)
            points.append(wall + d * depth)
            return BallPath(points, bounces, PathEnd.POCKET, d, pocket=pocket)
        if len(bounces) >= max_cushions:
            return BallPath(points, bounces, PathEnd.CUSHION, d)

        bounces.append(wall)
        out = Vec2(-d.x if vertical else d.x, -d.y if horizontal else d.y)
        if spin != 0.0 and vertical != horizontal:
            # O atrito do giro com a borracha empurra a bola ao longo da tabela.
            toward = Vec2(math.copysign(1.0, d.x) if vertical else 0.0, math.copysign(1.0, d.y) if horizontal else 0.0)
            along = Vec2(-toward.y, toward.x)
            out = (out + along * (spin * _SIDE_SPIN_KICK * abs(d.dot(toward)))).normalized()
            spin *= _SIDE_SPIN_DECAY
        d = out
        pos = wall
        remaining -= t_wall


def _mouth_at(table: Table, p: Vec2, d: Vec2, vertical: bool, horizontal: bool) -> Pocket | None:
    """A caçapa em cuja boca a bola chega: o ponto onde ela encosta na linha das tabelas fica entre as
    pontas (fora delas é borracha reta e a bola bate)."""
    r = table.ball_radius
    jaw = CORNER_JAW * r
    if horizontal:
        top = d.y < 0
        if p.x - table.left <= jaw:
            return table.pocket(PocketId.TOP_LEFT if top else PocketId.BOTTOM_LEFT)
        if table.right - p.x <= jaw:
            return table.pocket(PocketId.TOP_RIGHT if top else PocketId.BOTTOM_RIGHT)
        if abs(p.x - table.center_x) <= SIDE_JAW * r:
            return table.pocket(PocketId.TOP_MIDDLE if top else PocketId.BOTTOM_MIDDLE)
    if vertical:
        left = d.x < 0
        if p.y - table.top <= jaw:
            return table.pocket(PocketId.TOP_LEFT if left else PocketId.TOP_RIGHT)
        if table.bottom - p.y <= jaw:
            return table.pocket(PocketId.BOTTOM_LEFT if left else PocketId.BOTTOM_RIGHT)
    return None


def _drops(table: Table, pocket: Pocket, start: Vec2, d: Vec2) -> bool:
    """A bola cai se o centro atravessa a boca entre as duas pontas sem passar a menos de
    ``JAW_CLEARANCE`` raios de nenhuma delas. Bola rasante cruza a boca depois da ponta de lá."""
    a, b = table.mouth(pocket.id)
    ab = b - a
    n = Vec2(-ab.y, ab.x)
    if abs(d.dot(n)) < _EPS:
        return False
    s = (a - start).dot(n) / d.dot(n)
    if s < 0:  # já começou dentro da boca
        return True
    cross = start + d * s
    if not 0.0 < (cross - a).dot(ab) / ab.dot(ab) < 1.0:
        return False
    clearance = JAW_CLEARANCE * table.ball_radius
    return all(_distance_to_segment(tip, start, cross) >= clearance for tip in (a, b))


def _distance_to_segment(p: Vec2, a: Vec2, b: Vec2) -> float:
    ab = b - a
    length2 = ab.dot(ab)
    t = 0.0 if length2 < _EPS else min(max((p - a).dot(ab) / length2, 0.0), 1.0)
    return p.distance_to(a + ab * t)


def _first_ball_hit(table: Table, pos: Vec2, d: Vec2, balls, max_t: float):
    reach = 2 * table.ball_radius
    best = None
    for center in balls:
        mx, my = pos.x - center.x, pos.y - center.y
        c = mx * mx + my * my - reach * reach
        if c <= 0:  # marcadores sobrepostos: ignora em vez de travar a linha
            continue
        b = mx * d.x + my * d.y
        disc = b * b - c
        if b >= 0 or disc < 0:
            continue
        t = -b - math.sqrt(disc)
        if t <= max_t and (best is None or t < best[0]):
            best = (t, center)
    return best
