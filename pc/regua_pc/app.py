"""Janela de controle da Régua para PC."""

from __future__ import annotations

import argparse
import sys

import numpy as np
from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QApplication, QComboBox, QFileDialog, QFrame, QHBoxLayout, QLabel, QPushButton, QSlider,
    QVBoxLayout, QWidget,
)

from . import __version__, theme
from .capture import IS_WINDOWS, list_windows
from .icon import make_icon, make_pixmap
from .overlay import Overlay, PreviewWindow
from .settings import Settings
from .tracker import ImageSource, MonitorSource, Tracker, Update, WindowSource
from .widgets import CalibrationView, SpinPad, StatusPill, Toggle

TEST_ITEM = "Testar com um print…"


def _card(title: str) -> tuple[QFrame, QVBoxLayout]:
    frame = QFrame()
    frame.setObjectName("card")
    layout = QVBoxLayout(frame)
    layout.setContentsMargins(16, 14, 16, 16)
    layout.setSpacing(10)
    label = QLabel(title.upper())
    label.setObjectName("cardTitle")
    layout.addWidget(label)
    return frame, layout


def _hint(text: str) -> QLabel:
    label = QLabel(text)
    label.setObjectName("hint")
    label.setWordWrap(True)
    return label


class MainWindow(QWidget):
    def __init__(self, settings: Settings, image: np.ndarray | None = None, image_name: str = ""):
        super().__init__()
        self.settings = settings
        self.setWindowTitle("Régua")
        self.setWindowIcon(make_icon())
        self.setFixedWidth(440)
        self.overlay = Overlay(settings)
        self.preview = PreviewWindow(settings)
        self.tracker = Tracker(settings)
        self.tracker.updated.connect(self._on_update)
        self.tracker.calibrated.connect(self._on_calibrated)
        self._last_size: tuple[int, int] | None = None
        self._running = False
        self._test_image = image
        self._test_name = image_name

        root = QVBoxLayout(self)
        root.setContentsMargins(18, 18, 18, 14)
        root.setSpacing(12)
        root.addLayout(self._header())
        root.addWidget(self._window_card())
        self.toggle_button = QPushButton("Ligar régua")
        self.toggle_button.setObjectName("primary")
        self.toggle_button.setCursor(Qt.PointingHandCursor)
        self.toggle_button.clicked.connect(self._toggle_running)
        root.addWidget(self.toggle_button)
        root.addWidget(self._lines_card())
        root.addWidget(self._table_card())
        footer = QHBoxLayout()
        self.stats = QLabel("—")
        self.stats.setObjectName("footer")
        version = QLabel(f"v{__version__}")
        version.setObjectName("footer")
        footer.addWidget(self.stats)
        footer.addStretch(1)
        footer.addWidget(version)
        root.addLayout(footer)

        self._refresh_windows()
        self.tracker.start()
        if image is not None:
            self._select_test_item()
            self._toggle_running()

    # --- montagem ---

    def _header(self) -> QHBoxLayout:
        row = QHBoxLayout()
        logo = QLabel()
        logo.setPixmap(make_pixmap(44))
        row.addWidget(logo)
        texts = QVBoxLayout()
        texts.setSpacing(0)
        title = QLabel("Régua")
        title.setObjectName("title")
        subtitle = QLabel("8 Ball Pool no PC")
        subtitle.setObjectName("subtitle")
        texts.addWidget(title)
        texts.addWidget(subtitle)
        row.addLayout(texts)
        row.addStretch(1)
        self.status = StatusPill()
        row.addWidget(self.status, alignment=Qt.AlignTop)
        return row

    def _window_card(self) -> QFrame:
        frame, layout = _card("Janela do jogo")
        row = QHBoxLayout()
        self.windows = QComboBox()
        self.windows.setMinimumWidth(300)
        self.windows.activated.connect(self._on_window_chosen)
        refresh = QPushButton("⟳")
        refresh.setObjectName("icon")
        refresh.setToolTip("Atualizar a lista de janelas")
        refresh.clicked.connect(self._refresh_windows)
        row.addWidget(self.windows, 1)
        row.addWidget(refresh)
        layout.addLayout(row)
        layout.addWidget(_hint("Abra o 8 Ball Pool no Google Play Games e escolha a janela dele. "
                               "A régua desenha por cima dela sem atrapalhar os cliques."))
        return frame

    def _lines_card(self) -> QFrame:
        frame, layout = _card("Linhas")
        row = QHBoxLayout()
        row.addWidget(QLabel("Tabelas"))
        self.cushions = QSlider(Qt.Horizontal)
        self.cushions.setRange(0, 5)
        self.cushions.setValue(self.settings.cushions)
        self.cushions_value = QLabel(str(self.settings.cushions))
        self.cushions_value.setObjectName("value")
        self.cushions.valueChanged.connect(self._on_cushions)
        row.addWidget(self.cushions, 1)
        row.addWidget(self.cushions_value)
        layout.addLayout(row)

        body = QHBoxLayout()
        toggles = QVBoxLayout()
        toggles.setSpacing(10)
        self.use_balls = self._toggle_row(toggles, "Parar nas outras bolas e mostrar combinações", self.settings.use_balls)
        self.use_balls.toggled.connect(lambda v: self._set("use_balls", v))
        self.cue_after = self._toggle_row(toggles, "Mostrar a branca depois do choque", self.settings.show_cue_after)
        self.cue_after.toggled.connect(lambda v: self._set("show_cue_after", v))
        toggles.addWidget(_hint("Efeito: marque ao lado o mesmo ponto vermelho do jogo (duplo clique zera). "
                                "É uma estimativa; sem efeito a linha é exata."))
        body.addLayout(toggles, 1)
        self.spin = SpinPad()
        self.spin.set_spin(self.settings.spin_side, self.settings.spin_follow)
        self.spin.changed.connect(self._on_spin)
        body.addWidget(self.spin, alignment=Qt.AlignTop)
        layout.addLayout(body)
        return frame

    def _toggle_row(self, parent: QVBoxLayout, text: str, checked: bool) -> Toggle:
        row = QHBoxLayout()
        toggle = Toggle()
        toggle.setChecked(checked)
        label = QLabel(text)
        label.setWordWrap(True)
        row.addWidget(toggle, alignment=Qt.AlignTop)
        row.addWidget(label, 1)
        parent.addLayout(row)
        return toggle

    def _table_card(self) -> QFrame:
        frame, layout = _card("Mesa")
        self.calibration = CalibrationView()
        self.calibration.table_changed.connect(self._on_table_dragged)
        layout.addWidget(self.calibration)
        row = QHBoxLayout()
        auto = QPushButton("Calibrar automático")
        auto.clicked.connect(self._calibrate)
        self.adjust = QPushButton("Ajustar à mão")
        self.adjust.setCheckable(True)
        self.adjust.toggled.connect(self._on_adjust)
        row.addWidget(auto)
        row.addWidget(self.adjust)
        layout.addLayout(row)
        self.table_hint = _hint("As linhas ciano devem ficar na borda das tabelas e os círculos sobre as caçapas. "
                                "A cor da mesa não importa.")
        layout.addWidget(self.table_hint)
        return frame

    # --- janelas ---

    def _refresh_windows(self) -> None:
        current = self.windows.currentText()
        self.windows.clear()
        for w in list_windows():
            self.windows.addItem(w.title, w)
        if not IS_WINDOWS:
            self.windows.addItem(MonitorSource.title, "monitor")
        self.windows.addItem(TEST_ITEM, "test")
        wanted = self.settings.window_title or current
        index = self.windows.findText(wanted) if wanted else -1
        self.windows.setCurrentIndex(index if index >= 0 else 0)

    def _select_test_item(self) -> None:
        self.windows.setCurrentIndex(self.windows.findText(TEST_ITEM))

    def _on_window_chosen(self, _index: int) -> None:
        data = self.windows.currentData()
        if data == "test":
            path, _ = QFileDialog.getOpenFileName(self, "Escolha um print do jogo", "", "Imagens (*.png *.jpg *.jpeg)")
            if not path:
                return
            self._test_image = _load_image(path)
            self._test_name = path
        else:
            self.settings.window_title = self.windows.currentText()
            self.settings.save()
        if self._running:
            self.tracker.source = self._make_source()

    def _make_source(self):
        data = self.windows.currentData()
        if data == "test":
            return ImageSource(self._test_image, self._test_name) if self._test_image is not None else None
        if data == "monitor":
            return MonitorSource()
        if data is not None:
            return WindowSource(data.handle, data.title)
        return None

    # --- ações ---

    def _toggle_running(self) -> None:
        if self._running:
            self._running = False
            self.tracker.source = None
            self.overlay.hide()
            self.preview.hide()
        else:
            source = self._make_source()
            if source is None:
                self.status.set_status("sem_janela")
                return
            self._running = True
            self.tracker.source = source
            if isinstance(source, ImageSource):
                self.preview.show()
        self.toggle_button.setText("Desligar régua" if self._running else "Ligar régua")
        self.toggle_button.setProperty("running", self._running)
        self.toggle_button.style().unpolish(self.toggle_button)
        self.toggle_button.style().polish(self.toggle_button)

    def _set(self, name: str, value) -> None:
        setattr(self.settings, name, value)
        self.settings.save()
        self.overlay.update()
        self.preview.update()

    def _on_cushions(self, value: int) -> None:
        self.cushions_value.setText(str(value))
        self._set("cushions", value)

    def _on_spin(self, side: float, follow: float) -> None:
        self.settings.spin_side, self.settings.spin_follow = side, follow
        self._set("spin_side", side)

    def _calibrate(self) -> None:
        self.tracker.calibrate_now = True
        self.table_hint.setText("Calibrando… (deixe a mesa do jogo visível)")
        if not self._running:
            self.table_hint.setText("Ligue a régua para calibrar com a imagem do jogo.")

    def _on_calibrated(self, ok: bool) -> None:
        self.table_hint.setText("Mesa encontrada ✓" if ok else "Não achei as tabelas. Use “Ajustar à mão” e arraste os cantos.")

    def _on_adjust(self, editing: bool) -> None:
        self.calibration.editing = editing
        self.calibration.update()

    def _on_table_dragged(self, table) -> None:
        if self._last_size:
            self.settings.set_table(table, *self._last_size)
            self.settings.save()

    def _on_update(self, u: Update) -> None:
        self.status.set_status(u.status)
        if u.size:
            self._last_size = u.size
        if u.status in ("seguindo", "procurando"):
            self.stats.setText(f"{u.fps:.0f} quadros/s · detecção {u.detect_ms:.0f} ms")
        else:
            self.stats.setText("—")
        if u.thumbnail is not None:
            self.calibration.set_frame(u.thumbnail, u.thumbnail_scale)
        self.calibration.set_table(u.table)
        if not self._running:
            return
        if u.frame is not None:
            self.preview.set_scene(u.frame, u.result, u.table)
        elif u.rect is not None:
            self.overlay.show_over(u.rect)
            self.overlay.set_scene(u.result, u.table)
        else:
            self.overlay.set_scene(None, None)

    def closeEvent(self, event) -> None:
        self.tracker.stop()
        self.settings.save()
        self.overlay.close()
        self.preview.close()
        super().closeEvent(event)


def _load_image(path: str) -> np.ndarray:
    from PySide6.QtGui import QImage

    image = QImage(path).convertToFormat(QImage.Format_RGB888)
    w, h = image.width(), image.height()
    data = np.frombuffer(image.constBits(), dtype=np.uint8, count=image.bytesPerLine() * h)
    # Copia: os bytes pertencem ao QImage, que some ao sair da função.
    return data.reshape(h, image.bytesPerLine())[:, : 3 * w].reshape(h, w, 3).copy()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Régua: rota da bola por cima do 8 Ball Pool no PC.")
    parser.add_argument("--imagem", help="testar com um print do jogo em vez da janela")
    args = parser.parse_args(argv)
    app = QApplication(sys.argv[:1])
    app.setApplicationName("Régua")
    app.setStyleSheet(theme.STYLE)
    app.setWindowIcon(make_icon())
    image = _load_image(args.imagem) if args.imagem else None
    window = MainWindow(Settings.load(), image, args.imagem or "")
    window.show()
    return app.exec()
