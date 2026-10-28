"""Ponto de entrada: ``python regua.py`` (ou o Regua-PC.exe gerado pelo PyInstaller)."""

import sys

from regua_pc.app import main

if __name__ == "__main__":
    sys.exit(main())
