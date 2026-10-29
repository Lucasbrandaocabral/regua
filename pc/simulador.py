"""Simulador: sorteia mesas parecidas com as do 8 Ball Pool, roda a régua em cada uma e confere.

    python simulador.py                    # 200 cenas, resumo por tipo de tacada
    python simulador.py --cenas 500 --saida falhas   # guarda as cenas que falharam (PNG + o que deu)
    python simulador.py --tipo colado      # só um tipo: longe, perto, colado, tabela, bola_na_tabela

As cenas vêm de ``tests/game_scene.py`` com a resposta certa junto (branca, direção, anel, bola alvo).
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from regua_pc.vision import AimDetector  # noqa: E402
from tests.game_scene import KINDS, TABLE, random_scene  # noqa: E402

# Erro máximo aceito. Com a branca quase encostada no alvo a direção sai de dois pontos bem perto um
# do outro (a meio raio, meio pixel já dá uns 3°), então ali a tolerância é maior; o que importa é para
# onde a bola alvo vai, e isso continua com a tolerância normal.
MAX_CUE_PX = {"colado": 6.0}
MAX_CUE_PX_DEFAULT = 3.0
MAX_ANGLE = {"colado": 6.0}
MAX_ANGLE_DEFAULT = 1.0
MAX_GHOST_PX = 2.5
MAX_OBJECT_ANGLE = 1.5


def angle_between(a, b) -> float:
    c = max(-1.0, min(1.0, a.normalized().dot(b.normalized())))
    return math.degrees(math.acos(c))


def check(result, truth) -> str | None:
    """None se a régua acertou; senão, o motivo."""
    if result is None:
        return "não achou a mira"
    if result.cue.distance_to(truth.cue) > MAX_CUE_PX.get(truth.kind, MAX_CUE_PX_DEFAULT):
        return f"branca a {result.cue.distance_to(truth.cue):.1f} px"
    err = angle_between(result.direction, truth.direction)
    if err > MAX_ANGLE.get(truth.kind, MAX_ANGLE_DEFAULT):
        return f"direção errada por {err:.2f}°"
    if truth.ghost is None:
        if result.ghost is not None:
            return "viu bola alvo onde a branca bate na tabela"
        return None
    if result.ghost is None:
        return "não viu a bola alvo"
    if result.ghost.distance_to(truth.ghost) > MAX_GHOST_PX:
        return f"anel a {result.ghost.distance_to(truth.ghost):.1f} px"
    if result.object_ball is None:
        return "sem bola alvo"
    err = angle_between(result.object_ball - result.ghost, truth.object_ball - truth.ghost)
    if err > MAX_OBJECT_ANGLE:
        return f"bola alvo vai {err:.2f}° torta"
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cenas", type=int, default=200)
    ap.add_argument("--semente", type=int, default=1)
    ap.add_argument("--tipo", choices=KINDS)
    ap.add_argument("--saida", help="pasta para as cenas que falharam")
    ap.add_argument("--exportar", help="pasta para guardar todas as cenas (PNG + JSON)")
    args = ap.parse_args()

    stats = {k: [0, 0] for k in KINDS}
    failures = []
    started = time.perf_counter()
    for i in range(args.cenas):
        seed = args.semente * 100000 + i
        kind = args.tipo or KINDS[i % len(KINDS)]
        rgb, truth = random_scene(seed, kind)
        result = AimDetector().detect(rgb, TABLE)
        problem = check(result, truth)
        stats[kind][1] += 1
        if problem is None:
            stats[kind][0] += 1
        else:
            failures.append((seed, kind, problem, rgb, truth, result))
        for folder, keep in ((args.exportar, True), (args.saida, problem is not None)):
            if folder and keep:
                _save(folder, seed, rgb, truth, result, problem)
    elapsed = time.perf_counter() - started

    print(f"{args.cenas} cenas em {elapsed:.0f} s")
    total_ok = sum(v[0] for v in stats.values())
    for kind, (ok, n) in stats.items():
        if n:
            print(f"  {kind:15s} {ok:4d}/{n:<4d} ({100 * ok / n:.0f}%)")
    print(f"  {'total':15s} {total_ok:4d}/{args.cenas:<4d} ({100 * total_ok / args.cenas:.0f}%)")
    for seed, kind, problem, *_ in failures[:30]:
        print(f"    falhou: cena {seed} ({kind}): {problem}")
    return 0 if not failures else 1


def _save(folder, seed, rgb, truth, result, problem):
    from PIL import Image

    os.makedirs(folder, exist_ok=True)
    Image.fromarray(rgb).save(os.path.join(folder, f"cena-{seed}.png"))
    v = lambda p: None if p is None else [p.x, p.y]  # noqa: E731
    data = {
        "tipo": truth.kind, "problema": problem,
        "certo": {"branca": v(truth.cue), "direcao": v(truth.direction), "anel": v(truth.ring),
                  "fantasma": v(truth.ghost), "alvo": v(truth.object_ball)},
        "achado": None if result is None else {"branca": v(result.cue), "direcao": v(result.direction),
                                              "fantasma": v(result.ghost), "alvo": v(result.object_ball)},
    }
    with open(os.path.join(folder, f"cena-{seed}.json"), "w") as f:
        json.dump(data, f, indent=1)


if __name__ == "__main__":
    sys.exit(main())
