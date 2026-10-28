"""Cores e folha de estilo (tema escuro) da janela de controle."""

BACKGROUND = "#0E141B"
CARD = "#151F2A"
CARD_BORDER = "#22303E"
TEXT = "#E6EDF3"
MUTED = "#8B9BAA"
ACCENT = "#2EBD59"
ACCENT_HOVER = "#36D366"
DANGER = "#E5534B"
WARNING = "#F2B134"
CYAN = "#18FFFF"

STYLE = f"""
QWidget {{
    background: {BACKGROUND};
    color: {TEXT};
    font-family: "Segoe UI", "Inter", "Noto Sans", sans-serif;
    font-size: 13px;
}}
QFrame#card {{
    background: {CARD};
    border: 1px solid {CARD_BORDER};
    border-radius: 14px;
}}
QFrame#card QLabel, QFrame#card QWidget#transparent {{
    background: transparent;
}}
QLabel#title {{
    font-size: 22px;
    font-weight: 700;
}}
QLabel#subtitle, QLabel#hint, QLabel#footer {{
    color: {MUTED};
}}
QLabel#hint {{
    font-size: 12px;
}}
QLabel#cardTitle {{
    font-size: 12px;
    font-weight: 700;
    color: {MUTED};
    letter-spacing: 1px;
}}
QLabel#value {{
    font-weight: 700;
    min-width: 18px;
}}
QComboBox {{
    background: #0F1821;
    border: 1px solid {CARD_BORDER};
    border-radius: 8px;
    padding: 7px 10px;
}}
QComboBox:hover {{
    border-color: #35506A;
}}
QComboBox QAbstractItemView {{
    background: {CARD};
    border: 1px solid {CARD_BORDER};
    selection-background-color: #24405A;
    outline: none;
}}
QPushButton {{
    background: #1C2A37;
    border: 1px solid {CARD_BORDER};
    border-radius: 8px;
    padding: 8px 12px;
}}
QPushButton:hover {{
    background: #23364A;
}}
QPushButton:pressed {{
    background: #1A2632;
}}
QPushButton:checked {{
    background: #24405A;
    border-color: #3A6A92;
}}
QPushButton#primary {{
    background: {ACCENT};
    border: none;
    color: #06210F;
    font-size: 15px;
    font-weight: 700;
    padding: 12px;
    border-radius: 12px;
}}
QPushButton#primary:hover {{
    background: {ACCENT_HOVER};
}}
QPushButton#primary[running="true"] {{
    background: #2A3A4A;
    color: {TEXT};
}}
QPushButton#icon {{
    padding: 7px;
    min-width: 20px;
}}
QSlider::groove:horizontal {{
    height: 6px;
    background: #22303E;
    border-radius: 3px;
}}
QSlider::sub-page:horizontal {{
    background: {ACCENT};
    border-radius: 3px;
}}
QSlider::handle:horizontal {{
    background: {TEXT};
    width: 16px;
    margin: -6px 0;
    border-radius: 8px;
}}
"""
