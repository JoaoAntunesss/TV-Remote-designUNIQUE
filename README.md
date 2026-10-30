# Comando TV · design UNIQUE

Comando para a TV no telemóvel Android, por Wi‑Fi. Faz conjunto com o
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

## Como funciona
- Teclas, texto e abrir apps usam o serviço de comando que já vem no Google TV (o mesmo da app Google TV),
  portas 6466/6467 com TLS. Emparelha uma vez com o código de 6 caracteres que aparece na TV.
  A chave do telemóvel fica no cofre do Android (AndroidKeyStore).
- Lista de apps, "A dar na TV", fundo, fontes e imagem do ecrã vêm do ecrã inicial da TV (porta 8765).
- A TV é encontrada sozinha na rede (mDNS `_androidtvremote2._tcp`); também se pode escrever o IP.

## Compilar
O GitHub Actions compila e publica o APK em cada envio. À mão: `ANDROID_HOME=... ./build.sh`
(SDK platform 34 + build-tools 34, JDK 17; sem Gradle).
