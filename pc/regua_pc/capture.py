"""Achar a janela do jogo, saber onde ela está na tela e capturar a imagem dela.

No Windows usa a API do sistema (ctypes) para listar janelas e pegar a área interna delas, e o
``mss`` para capturar. Em outros sistemas só dá para capturar a tela inteira.
"""

from __future__ import annotations

import ctypes
import sys
from dataclasses import dataclass

import numpy as np

IS_WINDOWS = sys.platform == "win32"


@dataclass(frozen=True)
class Rect:
    """Retângulo em pixels físicos da tela."""

    left: int
    top: int
    width: int
    height: int


@dataclass(frozen=True)
class GameWindow:
    handle: int
    title: str


if IS_WINDOWS:
    from ctypes import wintypes

    _user32 = ctypes.windll.user32
    _EnumWindowsProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    # Tipos declarados: janelas são ponteiros de 64 bits e o padrão do ctypes seria int de 32.
    _user32.EnumWindows.argtypes = [_EnumWindowsProc, wintypes.LPARAM]
    _user32.IsWindowVisible.argtypes = [wintypes.HWND]
    _user32.IsWindow.argtypes = [wintypes.HWND]
    _user32.IsIconic.argtypes = [wintypes.HWND]
    _user32.GetWindowTextLengthW.argtypes = [wintypes.HWND]
    _user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    _user32.GetClientRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
    _user32.ClientToScreen.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.POINT)]
    _user32.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int,
                                     ctypes.c_int, ctypes.c_int, wintypes.UINT]
    try:
        _user32.SetWindowDisplayAffinity.argtypes = [wintypes.HWND, wintypes.DWORD]
    except AttributeError:
        pass
    _WDA_EXCLUDEFROMCAPTURE = 0x11
    _HWND_TOPMOST = wintypes.HWND(-1)
    _SWP_NOACTIVATE = 0x0010
    _SWP_SHOWWINDOW = 0x0040


def list_windows() -> list[GameWindow]:
    """Janelas visíveis com título (só no Windows), as mais prováveis de serem o jogo primeiro."""
    if not IS_WINDOWS:
        return []
    found: list[GameWindow] = []

    def callback(hwnd, _lparam):
        if not _user32.IsWindowVisible(hwnd):
            return True
        length = _user32.GetWindowTextLengthW(hwnd)
        if length == 0:
            return True
        buffer = ctypes.create_unicode_buffer(length + 1)
        _user32.GetWindowTextW(hwnd, buffer, length + 1)
        title = buffer.value.strip()
        rect = client_rect(hwnd)
        # As janelas da própria régua não entram na lista.
        if title and not title.startswith("Régua") and rect is not None and rect.width >= 320 and rect.height >= 200:
            found.append(GameWindow(int(hwnd), title))
        return True

    _user32.EnumWindows(_EnumWindowsProc(callback), 0)
    return sorted(found, key=lambda w: (-game_score(w.title), w.title.lower()))


def game_score(title: str) -> int:
    t = title.lower()
    if "8 ball" in t or "pool" in t:
        return 2
    if "google play games" in t:
        return 1
    return 0


def client_rect(handle: int) -> Rect | None:
    """Área interna (sem borda e barra de título) da janela, em pixels físicos da tela."""
    if not IS_WINDOWS:
        return None
    if not _user32.IsWindow(handle) or _user32.IsIconic(handle):
        return None
    rect = wintypes.RECT()
    if not _user32.GetClientRect(handle, ctypes.byref(rect)):
        return None
    origin = wintypes.POINT(0, 0)
    if not _user32.ClientToScreen(handle, ctypes.byref(origin)):
        return None
    width, height = rect.right - rect.left, rect.bottom - rect.top
    if width <= 0 or height <= 0:
        return None
    return Rect(origin.x, origin.y, width, height)


def exclude_from_capture(handle: int) -> bool:
    """Esconde a janela das capturas de tela (Windows 10 2004+): a régua não aparece no que ela
    mesma analisa. Devolve False se o sistema não suporta."""
    if not IS_WINDOWS:
        return False
    try:
        return bool(_user32.SetWindowDisplayAffinity(wintypes.HWND(handle), _WDA_EXCLUDEFROMCAPTURE))
    except (AttributeError, OSError):
        return False


def place_window(handle: int, rect: Rect) -> None:
    """Põe a janela exatamente sobre ``rect`` (em pixels físicos), por cima de tudo e sem foco."""
    if IS_WINDOWS:
        _user32.SetWindowPos(
            wintypes.HWND(handle), _HWND_TOPMOST, rect.left, rect.top, rect.width, rect.height,
            _SWP_NOACTIVATE | _SWP_SHOWWINDOW,
        )


class ScreenGrabber:
    """Captura um retângulo da tela como RGB. Crie e use na mesma thread (o mss pede isso)."""

    def __init__(self) -> None:
        import mss

        self._mss = mss.mss()

    def grab(self, rect: Rect) -> np.ndarray:
        shot = self._mss.grab({"left": rect.left, "top": rect.top, "width": rect.width, "height": rect.height})
        bgra = np.frombuffer(shot.raw, dtype=np.uint8).reshape(shot.height, shot.width, 4)
        return np.ascontiguousarray(bgra[..., 2::-1])

    def primary_monitor(self) -> Rect:
        m = self._mss.monitors[1]
        return Rect(m["left"], m["top"], m["width"], m["height"])

    def close(self) -> None:
        self._mss.close()
