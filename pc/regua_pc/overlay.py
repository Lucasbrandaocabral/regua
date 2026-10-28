"""Janelas que desenham a rota: a transparente por cima do jogo e a de teste com um print."""

from __future__ import annotations

import numpy as np
from PySide6.QtCore import QRectF, Qt
from PySide6.QtGui import QColor, QImage, QPainter
from PySide6.QtWidgets import QWidget

from . import theme
from .capture import IS_WINDOWS, Rect, exclude_from_capture, place_window
from .geometry import Table
from .render import draw_auto_shot
from .settings import Settings
from .vision import AimResult


class Overlay(QWidget):
    """Janela transparente, sempre por cima, que não recebe cliques: fica exatamente sobre a área do
    jogo e desenha em pixels da tela (os mesmos da captura)."""

    def __init__(self, settings: Settings):
        super().__init__(None, Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool
                         | Qt.WindowTransparentForInput | Qt.WindowDoesNotAcceptFocus | Qt.NoDropShadowWindowHint)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.setAttribute(Qt.WA_TransparentForMouseEvents)
        self.setWindowTitle("Régua (sobreposição)")
        self.settings = settings
        self._rect: Rect | None = None
        self._result: AimResult | None = None
        self._table: Table | None = None
        self._hidden_from_capture = False

    def show_over(self, rect: Rect) -> None:
        if not self.isVisible():
            self.show()
        if rect != self._rect:
            self._rect = rect
            if IS_WINDOWS:
                place_window(int(self.winId()), rect)
            else:
                dpr = self.devicePixelRatioF() or 1.0
                self.setGeometry(int(rect.left / dpr), int(rect.top / dpr), int(rect.width / dpr), int(rect.height / dpr))
        if IS_WINDOWS and not self._hidden_from_capture:
            # Fora da captura a régua nunca atrapalha a própria detecção.
            self._hidden_from_capture = exclude_from_capture(int(self.winId()))

    def set_scene(self, result: AimResult | None, table: Table | None) -> None:
        self._result, self._table = result, table
        self.update()

    def hideEvent(self, event) -> None:
        self._rect = None
        super().hideEvent(event)

    def paintEvent(self, _event) -> None:
        if self._result is None or self._table is None:
            return
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        dpr = self.devicePixelRatioF() or 1.0
        p.scale(1 / dpr, 1 / dpr)
        draw_auto_shot(p, self._result, self._table, self.settings)


class PreviewWindow(QWidget):
    """Mostra um print com a rota desenhada por cima (modo de teste, sem o jogo aberto)."""

    def __init__(self, settings: Settings):
        super().__init__()
        self.setWindowTitle("Régua – teste com print")
        self.resize(1100, 520)
        self.settings = settings
        self._image: QImage | None = None
        self._size = (1, 1)
        self._result: AimResult | None = None
        self._table: Table | None = None

    def set_scene(self, frame: np.ndarray, result: AimResult | None, table: Table | None) -> None:
        h, w = frame.shape[:2]
        if self._image is None or self._size != (w, h):
            self._image = QImage(frame.data, w, h, 3 * w, QImage.Format_RGB888).copy()
            self._size = (w, h)
        self._result, self._table = result, table
        self.update()

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.fillRect(self.rect(), QColor(theme.BACKGROUND))
        if self._image is None:
            return
        w, h = self._size
        k = min(self.width() / w, self.height() / h)
        ox, oy = (self.width() - w * k) / 2, (self.height() - h * k) / 2
        p.drawImage(QRectF(ox, oy, w * k, h * k), self._image)
        if self._result is None or self._table is None:
            p.setPen(QColor(theme.WARNING))
            p.drawText(self.rect().adjusted(0, 0, 0, -12), Qt.AlignHCenter | Qt.AlignBottom,
                       "A linha de mira do jogo não apareceu neste print.")
            return
        p.translate(ox, oy)
        p.scale(k, k)
        draw_auto_shot(p, self._result, self._table, self.settings)
