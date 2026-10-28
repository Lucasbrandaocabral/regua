"""Thread que captura a janela do jogo, acha a mira e manda o resultado para a interface."""

from __future__ import annotations

import time
from dataclasses import dataclass

import numpy as np
from PySide6.QtCore import QThread, Signal

from .calibration import find_table, guess_table
from .capture import Rect, ScreenGrabber, client_rect
from .geometry import Table
from .settings import Settings
from .vision import AimDetector, AimResult

MAX_FPS = 30
THUMBNAIL_EVERY = 0.5  # segundos entre miniaturas para a tela de calibração


class WindowSource:
    """A área interna de uma janela do Windows (o jogo)."""

    def __init__(self, handle: int, title: str):
        self.handle, self.title = handle, title

    def rect(self) -> Rect | None:
        return client_rect(self.handle)

    def grab(self, grabber: ScreenGrabber, rect: Rect) -> np.ndarray:
        return grabber.grab(rect)


class MonitorSource:
    """A tela principal inteira (para sistemas sem lista de janelas)."""

    title = "Tela inteira"

    def __init__(self) -> None:
        self._rect: Rect | None = None

    def rect(self) -> Rect | None:
        return self._rect

    def prepare(self, grabber: ScreenGrabber) -> None:
        self._rect = grabber.primary_monitor()

    def grab(self, grabber: ScreenGrabber, rect: Rect) -> np.ndarray:
        return grabber.grab(rect)


class ImageSource:
    """Um print salvo, para testar a régua sem o jogo aberto."""

    def __init__(self, image: np.ndarray, title: str):
        self.image, self.title = image, title

    def rect(self) -> Rect:
        return Rect(0, 0, self.image.shape[1], self.image.shape[0])

    def grab(self, grabber, rect: Rect) -> np.ndarray:
        return self.image


@dataclass
class Update:
    status: str  # "parado", "sem_janela", "procurando", "seguindo"
    rect: Rect | None = None
    size: tuple[int, int] | None = None
    table: Table | None = None
    result: AimResult | None = None
    fps: float = 0.0
    detect_ms: float = 0.0
    thumbnail: np.ndarray | None = None
    thumbnail_scale: float = 1.0
    frame: np.ndarray | None = None  # quadro inteiro (só no modo de teste com print)


class Tracker(QThread):
    updated = Signal(object)
    calibrated = Signal(bool)

    def __init__(self, settings: Settings):
        super().__init__()
        self.settings = settings
        self.source = None
        self.calibrate_now = False
        self._stop = False

    def stop(self) -> None:
        self._stop = True
        self.wait(3000)

    def run(self) -> None:
        grabber = None
        detector = AimDetector()
        previous: AimResult | None = None
        last_thumb = 0.0
        frame_times: list[float] = []
        try:
            while not self._stop:
                started = time.perf_counter()
                source = self.source
                if source is None:
                    self.updated.emit(Update("parado"))
                    time.sleep(0.2)
                    continue
                if grabber is None and not isinstance(source, ImageSource):
                    grabber = ScreenGrabber()
                if isinstance(source, MonitorSource) and source.rect() is None:
                    source.prepare(grabber)
                rect = source.rect()
                if rect is None:
                    previous = None
                    self.updated.emit(Update("sem_janela"))
                    time.sleep(0.5)
                    continue
                frame = source.grab(grabber, rect)
                h, w = frame.shape[:2]

                table = self.settings.table_for(w, h)
                if table is None or self.calibrate_now:
                    self.calibrate_now = False
                    found = find_table(frame)
                    if found is not None:
                        self.settings.set_table(found, w, h)
                        self.settings.save()
                        table = found
                    self.calibrated.emit(found is not None)
                    if table is None:
                        table = guess_table(w, h)

                t0 = time.perf_counter()
                result = detector.detect(frame, table, previous)
                detect_ms = (time.perf_counter() - t0) * 1000
                previous = result

                now = time.perf_counter()
                frame_times = [t for t in frame_times if now - t < 1.0] + [now]
                update = Update("seguindo" if result else "procurando", rect, (w, h), table, result,
                                fps=len(frame_times), detect_ms=detect_ms)
                if now - last_thumb >= THUMBNAIL_EVERY:
                    last_thumb = now
                    step = max(1, w // 480)
                    update.thumbnail = np.ascontiguousarray(frame[::step, ::step])
                    update.thumbnail_scale = 1.0 / step
                if isinstance(source, ImageSource):
                    update.frame = frame
                self.updated.emit(update)

                elapsed = time.perf_counter() - started
                time.sleep(max(0.0, 1.0 / MAX_FPS - elapsed))
        finally:
            if grabber is not None:
                grabber.close()
