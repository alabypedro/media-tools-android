# Universal Media Tools para Android

Versão Android do [Universal Media Tools](../universal-media-tools) (desktop). Baixa vídeos e
áudios de redes sociais e sites de mídia a partir de um link, com as mesmas regras de
qualidade do app desktop: mostra **só as resoluções que existem**, prefere streams
compatíveis e só troca o contêiner (sem recomprimir).

Por baixo usa a [youtubedl-android](https://github.com/JunkFood02/youtubedl-android), que
empacota **Python + yt-dlp + FFmpeg** no APK e roda o yt-dlp como processo filho (o mesmo
modelo do worker do desktop).

## ⚠️ Uso responsável

Use apenas para conteúdo que você tem autorização para baixar. O app não contorna DRM,
paywalls, controles de acesso nem conteúdo privado.

## Recursos (v0.1)

- **Compartilhar → Universal Media Tools** a partir do YouTube, Instagram, TikTok, navegador etc.
  O app extrai o link do texto compartilhado e já analisa.
- Colar o link (botão de colar no campo).
- Análise: thumbnail, título, autor, duração, visualizações e plataforma.
- **Vídeo**: "Melhor" ou uma das resoluções reais (com tamanho estimado), em MP4, MKV, WEBM ou Original.
- **Áudio**: MP3, M4A, OPUS, WAV, FLAC (só se a fonte for sem perdas) ou Original, com bitrate.
- Playlists e canais: "Baixar todos", com presets de prioridade ("Até 1080p"...).
- Fila com downloads simultâneos (1 a 4), progresso, velocidade e etapas (juntando, convertendo...).
- Continua com a tela desligada (serviço em primeiro plano + notificação de progresso).
- Notificação ao concluir (toque para abrir) ou falhar, com mensagem de erro clara.
- Histórico com Abrir, Compartilhar, Tentar de novo e Remover.
- Arquivos em **Download/Universal Media Tools** (aparecem no app Arquivos, na galeria e nos players).
- **yt-dlp atualizado automaticamente** uma vez por dia (desligável em Ajustes) e botão "Atualizar agora".
- Tema Material You (cores do papel de parede, claro/escuro).

Requer **Android 10 ou mais novo**.

## Instalar no celular

1. No celular, abra a [última versão](https://github.com/alabypedro/media-tools-android/releases/latest)
   e baixe `UniversalMediaTools-<versão>-arm64-v8a.apk`. Quase todo celular atual é arm64;
   `armeabi-v7a` é só para aparelhos bem antigos de 32 bits. Depois disso, as próximas versões
   chegam pelo próprio app.
2. Abra o arquivo no celular. O Android vai pedir para **permitir instalar apps desta fonte**
   (o app que abriu o APK: Arquivos, Drive, Chrome...). Permita e instale.
3. O Play Protect pode avisar que o app é desconhecido (não está na Play Store): "Instalar mesmo assim".
4. Na primeira abertura, permita as notificações. O app leva alguns segundos preparando e
   atualizando o yt-dlp.

Pelo USB com depuração ativada: `adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk`.

## Desenvolvimento

Pré-requisitos (já instalados nesta máquina):

- JDK 17 (Temurin), com `JAVA_HOME` apontando para ele.
- Android SDK em `%LOCALAPPDATA%\Android\Sdk` (platform 35, build-tools 35, platform-tools),
  referenciado em `local.properties` (`sdk.dir=C:/Users/<você>/AppData/Local/Android/Sdk`).

```bash
./gradlew testDebugUnitTest      # testes (parser do JSON, argumentos do yt-dlp, mensagens de erro)
./gradlew assembleDebug          # APKs de debug em app/build/outputs/apk/debug/
./gradlew assembleRelease        # APKs assinados em app/build/outputs/apk/release/
```

Sai um APK por arquitetura (`arm64-v8a`, `armeabi-v7a`, `x86_64`), cada um com ~70-80 MB
(Python + FFmpeg embutidos). O `x86_64` serve para o emulador.

### Publicar uma versão nova

Um comando só, igual ao app desktop:

```bash
python release.py "O que mudou"            # 0.2.0 -> 0.2.1 (correção)
python release.py --minor "O que mudou"    # 0.2.0 -> 0.3.0 (recurso novo)
python release.py --major "O que mudou"    # -> 1.0.0
python release.py --version 0.4.2 "..."    # versão exata
```

Ele sobe `versionName`/`versionCode` em `app/build.gradle.kts`, roda os testes, gera os APKs
assinados, copia para `dist/` como `UniversalMediaTools-<versão>-<abi>.apk` (com `.sha256`),
faz commit e push e publica em [Releases](https://github.com/alabypedro/media-tools-android/releases).
Se algo falhar antes do commit, a versão volta ao que era. `--skip-tests` e `--no-git` existem.

No celular, o app procura versão nova **ao abrir** (desligável em Ajustes) e em
**Ajustes → Verificar atualizações do app**. Aparece um aviso no topo do Início: "Atualizar"
baixa o APK da arquitetura do aparelho, confere o SHA-256 e abre o instalador do Android.
Na primeira vez o Android pede para permitir "instalar apps desta fonte" para o Universal Media Tools.

### Assinatura

O build de release lê `keystore.properties` (fora do git):

```properties
storeFile=C:/Users/pedro/.android/umt-release.jks
storePassword=...
keyAlias=umt
keyPassword=...
```

**Guarde uma cópia do `.jks` e da senha.** O Android só aceita atualizar o app com um APK
assinado pela mesma chave: se a chave se perder, será preciso desinstalar e instalar de novo
(perdendo o histórico).

### Emulador

```bash
emulator -avd umt_test          # AVD Pixel 6 / Android 15 x86_64 criado com avdmanager
adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
# simular "Compartilhar":
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://..." -n com.alabypedro.mediatools/.ui.MainActivity
```

## Arquitetura

```text
app/src/main/java/com/alabypedro/mediatools/
├── UmtApp.kt                 Application: cria Settings, Engine e DownloadRepository; inicia o motor
├── engine/
│   ├── Engine.kt             init do Python/FFmpeg, auto-update do yt-dlp, analyze(), download(), cancel(),
│   │                         etapas do progresso e mensagens de erro amigáveis
│   ├── Media.kt              MediaInfo/MediaFormat: parse do --dump-single-json, resoluções reais, estimativas
│   └── Selection.kt          escolha do usuário → argumentos do yt-dlp (porta do build_format_params do desktop)
├── download/
│   ├── DownloadRepository.kt fila, concorrência, cancelar/tentar de novo, histórico em downloads.json
│   ├── DownloadService.kt    serviço em primeiro plano (dataSync) + notificações
│   └── MediaStoreSaver.kt    copia o resultado para Download/Universal Media Tools via MediaStore
├── data/Settings.kt          preferências (SharedPreferences)
└── ui/                       Compose + Material 3: Início, Downloads, Ajustes
```

Cada download roda numa pasta temporária (`cache/jobs/<id>`). Ao terminar, todos os arquivos
gerados (um, ou vários numa playlist, com subpasta) são copiados para o armazenamento
compartilhado e a pasta temporária é apagada.

## Limitações conhecidas

- Só o downloader por enquanto: o conversor, o editor e o renomeador do desktop não foram portados.
- Sem **gallery-dl**: posts só de imagem (carrosséis do Instagram, galerias) e sites que no
  desktop usam o gallery-dl não funcionam. O yt-dlp cobre vídeo e áudio.
- Sem cookies/login: conteúdo que exige sessão (muito do Instagram, Facebook, X) pode falhar
  com "A plataforma exigiu login".
- Download interrompido (app fechado à força) não é retomado: aparece como falha, com "Tentar de novo".
- Android 15 limita serviços `dataSync` a ~6 h por dia.
- O app não está na Play Store (instalação manual do APK).
