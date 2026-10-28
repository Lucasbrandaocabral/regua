"""Componentes da janela de controle: chave liga/desliga, controle de efeito, calibração e status."""

from __future__ import annotations

import math

import numpy as np
from PySide6.QtCore import QPointF, QRectF, QSize, Qt, Signal
from PySide6.QtGui import QColor, QImage, QPainter, QPen
from PySide6.QtWidgets import QAbstractButton, QLabel, QSizePolicy, QWidget

from . import theme
from .geometry import Table
from .render import draw_table


class Toggle(QAbstractButton):
    """Chave estilo celular."""

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.setCheckable(True)
        self.setCursor(Qt.PointingHandCursor)
        self.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Fixed)

    def sizeHint(self) -> QSize:
        return QSize(42, 24)

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        track = QRectF(1, 3, self.width() - 2, self.height() - 6)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(theme.ACCENT if self.isChecked() else "#2A3A4A"))
        p.drawRoundedRect(track, track.height() / 2, track.height() / 2)
        d = self.height() - 4
        x = self.width() - d - 2 if self.isChecked() else 2
        p.setBrush(QColor("#FFFFFF"))
        p.drawEllipse(QRectF(x, 2, d, d))


class SpinPad(QWidget):
    """Bola branca com o ponto vermelho de efeito, como no jogo. Clique/arraste marca; duplo clique zera."""

    changed = Signal(float, float)

    LIMIT = 0.75

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.side = 0.0
        self.follow = 0.0
        self.setFixedSize(96, 96)
        self.setCursor(Qt.CrossCursor)
        self.setToolTip("Marque o mesmo ponto vermelho do jogo. Duplo clique tira o efeito.")

    def set_spin(self, side: float, follow: float) -> None:
        self.side, self.follow = side, follow
        self.update()

    def _ball(self) -> tuple[QPointF, float]:
        return QPointF(self.width() / 2, self.height() / 2), min(self.width(), self.height()) / 2 - 4

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        c, radius = self._ball()
        p.setPen(QPen(QColor("#2A3A4A"), 2))
        p.setBrush(QColor("#F3F5F7"))
        p.drawEllipse(c, radius, radius)
        p.setPen(QPen(QColor("#B8C2CC"), 1))
        p.drawLine(QPointF(c.x() - radius, c.y()), QPointF(c.x() + radius, c.y()))
        p.drawLine(QPointF(c.x(), c.y() - radius), QPointF(c.x(), c.y() + radius))
        p.setBrush(Qt.NoBrush)
        p.drawEllipse(c, radius * self.LIMIT, radius * self.LIMIT)
        dot = QPointF(c.x() + self.side * radius * self.LIMIT, c.y() - self.follow * radius * self.LIMIT)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor("#E53935"))
        p.drawEllipse(dot, radius * 0.13, radius * 0.13)

    def _set_from(self, pos: QPointF) -> None:
        c, radius = self._ball()
        side = (pos.x() - c.x()) / (radius * self.LIMIT)
        follow = -(pos.y() - c.y()) / (radius * self.LIMIT)
        length = math.hypot(side, follow)
        if length > 1:
            side, follow = side / length, follow / length
        if length < 0.12:
            side = follow = 0.0
        self.set_spin(side, follow)
        self.changed.emit(side, follow)

    def mousePressEvent(self, e) -> None:
        self._set_from(e.position())

    def mouseMoveEvent(self, e) -> None:
        self._set_from(e.position())

    def mouseDoubleClickEvent(self, _e) -> None:
        self.set_spin(0.0, 0.0)
        self.changed.emit(0.0, 0.0)


class CalibrationView(QWidget):
    """Miniatura do jogo com as tabelas e caçapas calibradas por cima. No modo de ajuste, arraste os
    cantos para as bordas das tabelas."""

    table_changed = Signal(object)  # Table em pixels do quadro

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self._image: QImage | None = None
        self._scale = 1.0  # pixels da miniatura por pixel do quadro
        self._table: Table | None = None
        self.editing = False
        self._drag: str | None = None

    def sizeHint(self) -> QSize:
        return QSize(400, 180)

    def minimumSizeHint(self) -> QSize:
        return QSize(240, 180)

    def set_frame(self, thumbnail: np.ndarray, scale: float) -> None:
        h, w = thumbnail.shape[:2]
        self._image = QImage(thumbnail.data, w, h, 3 * w, QImage.Format_RGB888).copy()
        self._scale = scale
        self.update()

    def set_table(self, table: Table | None) -> None:
        if self._drag is None:
            self._table = table
            self.update()

    def _view(self) -> tuple[float, float, float]:
        """Escala e deslocamento do quadro (pixels do jogo) para o widget."""
        if self._image is None:
            return 1.0, 0.0, 0.0
        k = min(self.width() / self._image.width(), self.height() / self._image.height())
        ox = (self.width() - self._image.width() * k) / 2
        oy = (self.height() - self._image.height() * k) / 2
        return k * self._scale, ox, oy

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor("#0B1117"))
        p.drawRoundedRect(QRectF(self.rect()), 10, 10)
        if self._image is None:
            p.setPen(QColor(theme.MUTED))
            p.drawText(self.rect(), Qt.AlignCenter, "A imagem do jogo aparece aqui\nquando a régua estiver ligada.")
            return
        k, ox, oy = self._view()
        p.drawImage(QRectF(ox, oy, self._image.width() * k / self._scale, self._image.height() * k / self._scale), self._image)
        if self._table is not None:
            p.translate(ox, oy)
            p.scale(k, k)
            draw_table(p, self._table, self.editing)

    def _corners(self) -> dict[str, QPointF]:
        t = self._table
        k, ox, oy = self._view()
        return {
            "tl": QPointF(ox + t.left * k, oy + t.top * k), "tr": QPointF(ox + t.right * k, oy + t.top * k),
            "bl": QPointF(ox + t.left * k, oy + t.bottom * k), "br": QPointF(ox + t.right * k, oy + t.bottom * k),
        }

    def mousePressEvent(self, e) -> None:
        if not self.editing or self._table is None:
            return
        pos = e.position()
        best = min(self._corners().items(), key=lambda kv: (kv[1] - pos).manhattanLength())
        if (best[1] - pos).manhattanLength() < 28:
            self._drag = best[0]

    def mouseMoveEvent(self, e) -> None:
        if self._drag is None or self._table is None:
            return
        k, ox, oy = self._view()
        x, y = (e.position().x() - ox) / k, (e.position().y() - oy) / k
        t = self._table
        left, top, right, bottom = t.left, t.top, t.right, t.bottom
        if "l" in self._drag:
            left = min(x, right - 50)
        if "r" in self._drag:
            right = max(x, left + 50)
        if "t" in self._drag:
            top = min(y, bottom - 25)
        if "b" in self._drag:
            bottom = max(y, top + 25)
        # O raio da bola acompanha a altura da mesa (proporção fixa no jogo).
        self._table = Table(left, top, right, bottom, (bottom - top) * 22.0 / 729.0)
        self.update()
        self.table_changed.emit(self._table)

    def mouseReleaseEvent(self, _e) -> None:
        self._drag = None


class StatusPill(QLabel):
    COLORS = {
        "parado": theme.MUTED,
        "sem_janela": theme.DANGER,
        "procurando": theme.WARNING,
        "seguindo": theme.ACCENT,
    }
    TEXTS = {
        "parado": "Desligada",
        "sem_janela": "Janela não encontrada",
        "procurando": "Procurando a mira",
        "seguindo": "Seguindo a mira",
    }

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.set_status("parado")

    def set_status(self, status: str) -> None:
        color = self.COLORS.get(status, theme.MUTED)
        self.setText(f"<span style='color:{color}'>●</span>&nbsp; {self.TEXTS.get(status, status)}")
        self.setStyleSheet(
            f"QLabel {{ background: #1A2632; border: 1px solid {theme.CARD_BORDER}; border-radius: 12px;"
            f" padding: 4px 10px; font-size: 12px; }}"
        )
