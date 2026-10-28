"""Ícone da régua desenhado em código (mesmo desenho do ícone do app Android)."""

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import QColor, QIcon, QPainter, QPainterPath, QPen, QPixmap


def make_pixmap(size: int = 256) -> QPixmap:
    pix = QPixmap(size, size)
    pix.fill(Qt.transparent)
    p = QPainter(pix)
    p.setRenderHint(QPainter.Antialiasing)
    p.scale(size / 108, size / 108)
    p.setPen(Qt.NoPen)
    p.setBrush(QColor("#0B5E8E"))
    p.drawRoundedRect(QRectF(4, 4, 100, 100), 22, 22)
    pen = QPen(QColor("#7FD3FF"), 4, Qt.SolidLine, Qt.RoundCap)
    p.setPen(pen)
    p.drawLine(QPointF(28, 30), QPointF(80, 30))
    p.setPen(Qt.NoPen)
    p.setBrush(QColor("#06263A"))
    p.drawEllipse(QPointF(76, 74), 8, 8)
    p.setPen(QPen(QColor("#00E676"), 2.5))
    p.setBrush(Qt.NoBrush)
    p.drawEllipse(QPointF(76, 74), 8, 8)
    path = QPainterPath(QPointF(34, 72))
    path.lineTo(54, 37)
    path.lineTo(76, 74)
    p.setPen(QPen(QColor("#FFEB3B"), 4, Qt.SolidLine, Qt.RoundCap, Qt.RoundJoin))
    p.drawPath(path)
    p.setPen(Qt.NoPen)
    p.setBrush(QColor("#FFFFFF"))
    p.drawEllipse(QPointF(34, 72), 8, 8)
    p.end()
    return pix


def make_icon() -> QIcon:
    return QIcon(make_pixmap())
