# Comando TV · design UNIQUE

Comando para a TV no telemóvel Android, por Wi‑Fi. Funciona com Google TV, Android TV e boxes Android (incluindo as boxes baratas sem serviços Google). Faz conjunto com o
[TV-Debloat-designUNIQUE](https://github.com/JoaoAntunesss/TV-Debloat-designUNIQUE) e tem o mesmo aspeto:
fundo escuro com brilho, vidro, branco quando se carrega.

**[Descarregar o APK](https://github.com/JoaoAntunesss/TV-Remote-designUNIQUE/releases/latest/download/ComandoTV.apk)**

## O que faz
- **Zona de toque:** desliza para mexer, toca para OK, mantém para OK longo.
- **Botões:** Voltar, Início, Play/Pausa, Menu, volume (−, sem som, +), pesquisar, escrever na TV, ⏪ ⏩, canal ▲ ▼, ligar/desligar.
- **Ecrã da TV no telemóvel** (*Ver aqui*): a imagem da TV em direto, que também serve de zona de toque.
- **A dar na TV:** o que está a tocar agora, com a capa; toca para abrir.
- **Na TV:** lareira, relógio, fontes HDMI, ecrã dividido, todas as apps, definições.
- **Fundo do ecrã inicial:** canal AntunesPlayer, lareira ou fotos.
- **Apps:** as apps da TV com as imagens delas; toca e abre na TV.
- Vertical ou deitado (duas colunas, como um tablet).

## Que TVs e boxes funcionam
| A TV ou box tem… | O que funciona |
|---|---|
| O ecrã inicial **TV-Debloat-designUNIQUE** | Tudo: lareira, relógio, fontes, ecrã dividido, fundo, apps com imagem, ecrã da TV, «A dar na TV». |
| Google TV / Android TV (serviço de comando da Google) | Comando completo, escrever, abrir apps conhecidas (Netflix, YouTube, Prime Video, Disney+…), fontes, definições. |
| **Depuração por rede (ADB)** ligada, por exemplo numa box Android | Comando completo e ainda: lista das apps instaladas, ecrã da TV no telemóvel em vídeo, fluido (se a box não deixar gravar o ecrã, passa a imagens) e «A dar na TV». |

A app descobre sozinha o que cada TV ou box consegue e mostra só as funções que lá funcionam.

**Box Android que não aparece?** Na box: Definições › Acerca › carrega 7 vezes em «Compilação». Depois, em Opções de programador,
liga «Depuração USB» e «Depuração por rede» (ou «ADB por Wi‑Fi»). Na primeira ligação, a TV pergunta «Permitir depuração?»:
marca «Permitir sempre» e carrega em OK.

## Como funciona
- Teclas, texto e abrir apps usam o serviço de comando que já vem no Google TV (o mesmo da app Google TV),
  portas 6466/6467 com TLS. Emparelha uma vez com o código de 6 caracteres que aparece na TV.
  A chave do telemóvel fica no cofre do Android (AndroidKeyStore).
- Lista de apps, "A dar na TV", fundo, fontes e imagem do ecrã vêm do ecrã inicial da TV (porta 8765).
- Boxes sem o serviço da Google: ADB por Wi‑Fi (porta 5555), com uma chave RSA do telemóvel que a box aceita uma vez.
  Teclas, texto, apps, imagem do ecrã e «A dar na TV» vão pela shell da box.
- A TV é encontrada sozinha na rede (mDNS `_androidtvremote2._tcp`); as boxes são procuradas na rede (portas 6466 e 5555).
  Também se pode escrever o IP.

## Compilar
O GitHub Actions compila e publica o APK em cada envio. À mão: `ANDROID_HOME=... ./build.sh`
(SDK platform 34 + build-tools 34, JDK 17; sem Gradle).
