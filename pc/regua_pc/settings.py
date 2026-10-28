"""Configurações salvas entre um uso e outro (JSON na pasta do usuário)."""

from __future__ import annotations

import json
import os
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path

from .geometry import Table


def settings_path() -> Path:
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home()))
        return base / "Regua" / "settings.json"
    return Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "regua" / "settings.json"


@dataclass
class Settings:
    window_title: str = ""
    cushions: int = 2
    use_balls: bool = True  # outras bolas da mesa param as linhas e geram combinações
    show_cue_after: bool = True
    spin_side: float = 0.0
    spin_follow: float = 0.0
    # Mesa em frações da área da janela do jogo: esquerda, topo, direita, baixo (largura/altura) e raio
    # da bola (fração da altura). None = calibrar automaticamente.
    table: list[float] | None = field(default=None)

    def table_for(self, width: int, height: int) -> Table | None:
        if not self.table or len(self.table) != 5:
            return None
        l, t, r, b, radius = self.table
        return Table(l * width, t * height, r * width, b * height, radius * height)

    def set_table(self, table: Table, width: int, height: int) -> None:
        self.table = [table.left / width, table.top / height, table.right / width, table.bottom / height,
                      table.ball_radius / height]

    @classmethod
    def load(cls) -> "Settings":
        try:
            data = json.loads(settings_path().read_text(encoding="utf-8"))
            known = {k: v for k, v in data.items() if k in cls.__dataclass_fields__}
            return cls(**known)
        except (OSError, ValueError, TypeError):
            return cls()

    def save(self) -> None:
        path = settings_path()
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(asdict(self), indent=2, ensure_ascii=False), encoding="utf-8")
        except OSError:
            pass
