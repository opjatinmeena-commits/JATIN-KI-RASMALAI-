# Java Discord Music Bot

Java 17 + JDA 6 + Lavalink 4. Features:

- Slash commands: `/play`, `/queue`, `/skip`, `/pause`, `/resume`, `/stop`
- `/loop` modes: off, track, queue
- `/volume` (0–100)
- `/nowplaying` with Pause / Resume / Skip / Stop buttons
- `/247 enabled:true|false` to stay in the voice channel when the queue is empty

## Important notes

1. Lavalink is a separate audio server. The Discord bot connects to it.
2. This starter uses Lavalink's built-in SoundCloud search (`scsearch:`) for text searches. Direct URLs work only when the relevant Lavalink source supports them.
3. YouTube is disabled in the included Lavalink config. Do not assume YouTube links/search will work unless you separately configure a currently supported and permitted Lavalink source plugin.
4. Render's free services are not suitable for reliable always-on voice bots. For 24/7 hosting, use always-on paid Render services for both the bot worker and Lavalink node.
5. The queue and 24/7 setting are in memory. They reset when the bot restarts.

## Run locally

Requirements: Java 17+, Maven 3.9+, and a Lavalink v4 server.

1. Start Lavalink with `lavalink/application.yml`, setting `LAVALINK_PASSWORD`.
2. Copy `.env.example` to `.env` and fill in your values.
3. Export the variables in your terminal (or configure them in your IDE):

   macOS/Linux:
   ```bash
   export BOT_TOKEN="your_discord_bot_token"
   export LAVALINK_URI="ws://localhost:2333"
   export LAVALINK_PASSWORD="your_lavalink_password"
   mvn clean package
   java -jar target/java-discord-music-bot-1.0.0.jar
   ```

   PowerShell:
   ```powershell
   $env:BOT_TOKEN="your_discord_bot_token"
   $env:LAVALINK_URI="ws://localhost:2333"
   $env:LAVALINK_PASSWORD="your_lavalink_password"
   mvn clean package
   java -jar target/java-discord-music-bot-1.0.0.jar
   ```

## Discord bot setup

1. In the Discord Developer Portal, create an application and add a Bot.
2. Copy the bot token into `BOT_TOKEN`. Never put it in GitHub.
3. Under OAuth2 > URL Generator, select `bot` and `applications.commands`.
4. Give it the permissions it needs: View Channels, Send Messages, Embed Links, Connect, and Speak.
5. Invite it to your server using the generated URL.
6. Make sure the bot can see the text channel where you will use commands.

## GitHub + Render deployment

You need two Render services because Lavalink is separate from the Java Discord bot.

### A. Upload to GitHub

1. Create a new GitHub repository.
2. Upload the project contents (the files in this folder, not the outer ZIP).
3. Do not upload `.env`, bot tokens, or passwords.

### B. Deploy the Lavalink service

1. In Render, create a **Private Service** connected to the same GitHub repository.
2. Choose Docker as the runtime.
3. Set the Dockerfile path to `lavalink/Dockerfile`.
4. Use an always-on paid instance and keep it in the same Render region as the bot.
5. Add environment variable `LAVALINK_PASSWORD` with a long random password.
6. Deploy it. Its private hostname will normally be based on the service name, for example `lavalink:2333`. Use the exact private hostname shown by Render.

### C. Deploy the Java bot

1. Create a **Background Worker** from the same GitHub repository.
2. Choose Docker as the runtime and use the root `Dockerfile`.
3. Add these environment variables:
   - `BOT_TOKEN` = your Discord bot token
   - `LAVALINK_URI` = `ws://<your-lavalink-private-hostname>:2333`
   - `LAVALINK_PASSWORD` = exactly the same password used by Lavalink
4. Use an always-on paid instance for continuous operation.
5. Deploy. Check the logs for `Music bot is online`.

### D. Updating

Push changes to GitHub. Render can automatically redeploy from the connected branch.

## Troubleshooting

- **Bot offline:** check `BOT_TOKEN` and Render worker logs.
- **Lavalink connection refused:** confirm the Lavalink private service is running, both services are in the same region, and `LAVALINK_URI` uses its private hostname.
- **Password mismatch:** `LAVALINK_PASSWORD` must match on both services.
- **No search results:** built-in text search is SoundCloud (`scsearch:`). Try a supported direct URL or configure an appropriate source plugin.
- **Bot joins but is silent:** verify the Lavalink service logs, Connect/Speak permissions, and source support.
- **Commands not visible:** wait a short time after the bot starts and verify it was invited with the `applications.commands` scope.

## Security

If a token is accidentally committed to GitHub, reset it immediately in the Discord Developer Portal. Removing it from the latest commit alone is not enough.
