# Régua

App Android que desenha a rota da bola **por cima do jogo de sinuca** (feito com o 8 Ball Pool em mente).
No modo automático ela segue sozinha a bola branca e a linha de mira do jogo; no manual você posiciona
a régua sobre a bola branca e a bola alvo. Ela mostra:

- **linha amarela**: caminho da branca, com até 5 tabelas;
- **linha azul**: para onde a bola alvo vai depois do choque (fica **verde** quando cai na caçapa);
- **linha tracejada**: a branca depois do choque (fica **vermelha** se ela também for cair);
- **círculo tracejado**: a "bola fantasma", onde a branca está no instante do choque;
- o **ângulo de corte** enquanto você ajusta.

A régua não mexe no jogo: ela só desenha por cima. No modo manual ela nem olha a tela; no automático
ela usa a gravação de tela do Android para achar a linha branca do jogo (veja abaixo). A cor da mesa não importa.

## Instalar

1. No celular, baixe o APK: <https://github.com/Lucasbrandaocabral/Regua/releases/download/apk/regua.apk>
   (o GitHub Actions gera esse arquivo a cada push).
2. Abra o arquivo e permita "instalar apps desconhecidos" para o navegador ou gerenciador de arquivos.
   O Android pode avisar que o app não vem da Play Store; é esperado para apps instalados por fora.
3. Abra a Régua e toque em **Permitir sobreposição** (o Android precisa disso para desenhar por cima de outro app).

Funciona no Android 8 ou mais novo. No iPhone não é possível: o iOS não deixa um app desenhar por cima de outro.

## Usar

### Automático

1. Deixe marcado "Seguir a linha do jogo sozinha" e toque em **Iniciar régua**.
2. O Android pergunta se a Régua pode gravar/transmitir a tela: confirme (tela inteira). O jogo abre.
3. É só jogar. Enquanto você gira o taco, a régua acha a bola branca e a linha branca do jogo e continua
   a partir de onde a linha do jogo acaba: as tabelas da branca em amarelo, a bola alvo em azul (verde
   quando cai) e a branca depois do choque em lilás (vermelha se ela também cai).

O botão **AUTO** do painel mostra o estado: verde = seguindo a linha; amarelo = a linha do jogo não está
aparecendo (vez do adversário, bolas andando); cinza = desligado (toque para ligar de novo).

A bola que o círculo do jogo toca é sempre a bola alvo no automático (o botão de alvo só vale no manual).
A régua também acha as outras bolas paradas na mesa: as linhas param quando chegam numa delas (com um
círculo tracejado onde encostam) e, se a bola alvo bater em outra bola, ela mostra para onde essa outra vai
(combinação). No triângulo fechado da saída as posições das bolas são aproximadas. Os quadros da tela são analisados no
próprio celular, só na hora: nada é salvo nem enviado. O Android mostra o aviso de gravação enquanto o
automático está ligado, e no Android 15 ou mais novo o jogo consegue saber que a tela está sendo gravada.

### Manual

1. Toque em **Iniciar régua** (com o automático desmarcado, ou desligue o AUTO no painel). O jogo abre
   com um painel de botões na borda esquerda.
2. Toque em **MIRAR**. Arraste o círculo branco até a bola branca e o laranja até a bola que você quer encaçapar.
   Arraste em qualquer outro lugar para girar a linha (quanto mais longe da branca, mais fino o ajuste).
3. Toque em **JOGAR**: a linha continua na tela e os toques voltam para o jogo.
   Gire o taco até a linha do jogo ficar em cima da linha da régua e dê a tacada.

Botões do painel, de cima para baixo:

| Botão | O que faz |
| --- | --- |
| Setas de 4 pontas | Arraste para mudar o painel de lugar; toque para recolher/abrir |
| MIRAR / JOGAR | Alterna entre ajustar a régua e jogar |
| AUTO | Liga/desliga a mira automática (pede para gravar a tela) |
| Setas redondas | Giram a linha 0,1° por toque (segure para girar sem parar) |
| Número "tab." | Quantas tabelas a linha mostra (0 a 5) |
| Alvo | Liga/desliga a bola alvo |
| Bolinha com ponto vermelho | Efeito: marque o mesmo ponto que você marcou no jogo |
| Mesa | Calibração |
| X | Fecha a régua |

### Efeito

Efeito em cima (segue) ou embaixo (puxa) muda o caminho da branca depois do choque.
Efeito lateral muda o ângulo de saída da branca nas tabelas. O jogo não publica a física dele, então esses
desvios são uma **estimativa**; a linha sem efeito é geometria exata.

### Força

A força do taco e da tacada não muda a direção das linhas, só até onde as bolas chegam.
A régua mostra a rota completa; se a bola tem força para chegar lá é com você.

## Calibração

Os valores padrão foram medidos num print 2408×1080 do 8 Ball Pool (todas as mesas do jogo têm a mesma
geometria, só muda a cor). Se as linhas não baterem com as tabelas no seu celular:

1. Toque no botão **Mesa** do painel.
2. Arraste os pontos até a borda das tabelas, onde a bola encosta. Os círculos tracejados devem cair em cima das caçapas.
3. Ponha o círculo branco sobre a bola branca e puxe a alça ao lado até ele ficar do tamanho dela.
4. Toque em **JOGAR** para salvar.

Se algo der errado, "Restaurar calibração padrão" no app volta aos valores medidos.

## Jogo limpo

Usar a régua em partidas online contra outras pessoas pode violar os termos do jogo e levar a banimento da conta.
Ela é ótima para treinar no modo offline/treino e entender ângulos e tabelas.

## Desenvolvimento

- Kotlin puro, sem bibliotecas além do Android: `geometry/` tem a física e `vision/` acha a mira do jogo
  num quadro da tela (os dois testados em `app/src/test`, a visão com mesas desenhadas pelos testes);
  `RulerView` desenha, `ControlPanel` é o painel, `ScreenTracker` grava a tela e `OverlayService` mantém
  as janelas por cima do jogo.
- `./gradlew testReleaseUnitTest assembleRelease` roda os testes e gera `app/build/outputs/apk/release/app-release.apk`.
- O APK é assinado com `app/regua.keystore`, uma chave **sem segredo** versionada de propósito para que todo
  build atualize por cima do anterior. Para distribuir o app, gere uma chave própria e guarde-a fora do repositório.
