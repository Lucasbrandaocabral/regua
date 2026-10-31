# Régua para PC

Versão para Windows da Régua, para quem joga o 8 Ball Pool pelo **Google Play Games no PC**. Ela
captura a janela do jogo, acha a bola branca e a linha de mira e desenha por cima, numa camada
transparente que não pega os cliques:

- **amarelo**: tabelas da branca depois de onde a linha do jogo acaba;
- **azul**: a bola alvo (fica **verde** quando cai), parando na próxima bola que ela encostar e mostrando
  para onde essa outra vai (combinação);
- **lilás**: a branca depois do choque (fica **vermelha** se ela também for cair);
- **círculo tracejado**: onde uma bola encosta em outra, ou na ponta da boca de uma caçapa (aí a
  linha não fica verde: a bola bate na boca e não cai limpa).

É o mesmo cálculo e a mesma visão do app Android, portados para Python (NumPy + PySide6).

## Instalar

**Jeito fácil:** baixe o executável e abra:
<https://github.com/Lucasbrandaocabral/Regua/releases/download/apk/Regua-PC.exe>

O Windows pode avisar "O Windows protegeu o computador" porque o programa não é assinado: clique em
**Mais informações → Executar assim mesmo**. Alguns antivírus desconfiam de executáveis feitos com
PyInstaller; se isso acontecer, use o jeito pelo código.

**Pelo código (Python 3.11 ou mais novo):**

```
cd pc
pip install -r requirements.txt
python regua.py
```

## Usar

1. Abra o 8 Ball Pool no Google Play Games.
2. Abra a Régua, escolha a janela do jogo na lista (⟳ atualiza a lista) e clique em **Ligar régua**.
3. Na primeira vez a mesa é calibrada sozinha. Em **Mesa**, as linhas ciano devem ficar na borda das
   tabelas; se não ficarem, clique em **Calibrar automático** com a mesa à mostra, ou em **Ajustar à mão**
   e arraste os cantos.
4. Jogue normalmente: a régua segue a sua mira. O selo no topo mostra "Seguindo a mira" (verde) ou
   "Procurando a mira" (amarelo, quando a linha do jogo não aparece: vez do adversário, bolas andando).
   No rodapé, "9/12 quadros com mira" diz em quantos dos quadros analisados no último segundo ela achou
   a mira.

Em **Linhas** você escolhe quantas tabelas mostrar (0 a 5), se as linhas param nas outras bolas, se mostra
a branca depois do choque e o efeito (marque o mesmo ponto vermelho do jogo; duplo clique zera; é uma
estimativa).

Sem o jogo aberto dá para testar com um print: escolha **Testar com um print…** na lista, ou rode
`python regua.py --imagem print.png`.

## Problemas comuns

- **"Janela não encontrada"**: a janela do jogo foi fechada ou minimizada. Abra o jogo e clique em ⟳.
- **Fica sempre "Procurando a mira"**: confira a calibração em **Mesa**. Se a miniatura do jogo aparece
  preta, o Windows não está deixando capturar essa janela.
- **A linha do jogo está na tela e a régua não acha**: clique em **Guardar quadro**. A régua guarda em
  Imagens\Regua o quadro exato que estava analisando (o nome diz `com-mira` ou `sem-mira`) e uma cópia
  `-anotado` com a mesa que ela usou em ciano e o que achou em magenta. O quadro puro abre em
  **Testar com um print…**, então é ele (e não uma foto do monitor) que serve para investigar o problema.
- **As linhas aparecem deslocadas**: use o jogo e a Régua no monitor principal, com a escala do Windows
  igual nos monitores.

As imagens da tela são analisadas só no seu computador, na hora; nada é enviado, e só fica salvo o
quadro que você mandar guardar. Usar a régua em partidas online contra outras pessoas pode violar os
termos do jogo.

## Desenvolvimento

- `regua_pc/geometry.py`: física (reflexões, caçapas, bola fantasma, efeito, combinações).
- `regua_pc/vision.py` e `regua_pc/balls.py`: acham a mira do jogo e as bolas num quadro.
- `regua_pc/calibration.py`: acha as bordas das tabelas sozinho.
- `regua_pc/capture.py`, `tracker.py`, `overlay.py`, `app.py`: janela do Windows, captura, camada
  transparente e janela de controle.
- `regua_pc/snapshot.py`: guarda o quadro analisado (puro e anotado) pelo botão **Guardar quadro**.
- Testes: `python -m pytest -q tests` (mesas desenhadas pelos testes, sem precisar do jogo).
- Simulador: `python simulador.py` sorteia mesas parecidas com as do 8 Ball Pool (tamanho de celular,
  bolas lisas e listradas, brilho, sombra, taco e as linhas do jogo), roda a régua em cada uma e mostra
  quantas ela acertou por tipo de tacada: longe, perto, colado (branca quase encostada no alvo, com o
  anel por cima dela), tabela e bola_na_tabela. `--saida pasta` guarda as cenas que falharam e
  `--exportar pasta` guarda todas (PNG + JSON com a resposta certa), para testar outra versão da visão.
- O GitHub Actions roda os testes e gera o `Regua-PC.exe` com PyInstaller a cada push.
