package bot;

import dev.arbjerg.lavalink.client.Helpers;
import dev.arbjerg.lavalink.client.LavalinkClient;
import dev.arbjerg.lavalink.client.NodeOptions;
import dev.arbjerg.lavalink.client.Link;
import dev.arbjerg.lavalink.client.event.TrackEndEvent;
import dev.arbjerg.lavalink.client.player.*;
import dev.arbjerg.lavalink.libraries.jda.JDAVoiceUpdateListener;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MusicBot extends ListenerAdapter {
    private final LavalinkClient lavalink;
    private final Map<Long, GuildMusic> music = new ConcurrentHashMap<>();
    private JDA jda;

    public MusicBot() throws InterruptedException {
        String token = requiredEnv("BOT_TOKEN");
        String nodeUri = requiredEnv("LAVALINK_URI");
        String nodePassword = requiredEnv("LAVALINK_PASSWORD");

        lavalink = new LavalinkClient(Helpers.getUserIdFromToken(token));
        lavalink.addNode(new NodeOptions.Builder()
                .setName("render-lavalink")
                .setServerUri(nodeUri)
                .setPassword(nodePassword)
                .build());

        lavalink.on(TrackEndEvent.class).subscribe(event -> {
            if (!"FINISHED".equals(event.getEndReason().name())) return;
            GuildMusic state = music.get(event.getGuildId());
            if (state != null) advance(event.getGuildId(), state);
        }, error -> System.err.println("Lavalink event error: " + error.getMessage()));

        jda = JDABuilder.createDefault(token)
                .setVoiceDispatchInterceptor(new JDAVoiceUpdateListener(lavalink))
                .enableIntents(GatewayIntent.GUILD_VOICE_STATES)
                .enableCache(CacheFlag.VOICE_STATE)
                .addEventListeners(this)
                .build()
                .awaitReady();

        registerCommands();
        System.out.println("Music bot is online as " + jda.getSelfUser().getName());
    }

    public static void main(String[] args) throws InterruptedException {
        new MusicBot();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private void registerCommands() {
        jda.updateCommands().addCommands(
                Commands.slash("play", "Play a song or add it to the queue")
                        .addOption(OptionType.STRING, "query", "Song title or supported URL", true),
                Commands.slash("queue", "Show the upcoming songs"),
                Commands.slash("skip", "Skip the current song"),
                Commands.slash("pause", "Pause playback"),
                Commands.slash("resume", "Resume playback"),
                Commands.slash("stop", "Stop playback and clear the queue"),
                Commands.slash("loop", "Set loop mode")
                        .addOptions(new OptionData(OptionType.STRING, "mode", "off, track, or queue", true)
                                .addChoice("Off", "off")
                                .addChoice("Track", "track")
                                .addChoice("Queue", "queue")),
                Commands.slash("volume", "Set playback volume")
                        .addOption(OptionType.INTEGER, "percent", "Volume from 0 to 100", true),
                Commands.slash("nowplaying", "Show the current song and controls"),
                Commands.slash("247", "Keep the bot connected when the queue is empty")
                        .addOption(OptionType.BOOLEAN, "enabled", "Enable or disable 24/7 mode", true)
        ).queue();
    }

    private GuildMusic state(long guildId) {
        return music.computeIfAbsent(guildId, ignored -> new GuildMusic());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            event.reply("Use this command inside a server.").setEphemeral(true).queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        GuildMusic state = state(guildId);
        Link link = lavalink.getOrCreateLink(guildId);

        switch (event.getName()) {
            case "play" -> play(event, state, link);
            case "queue" -> showQueue(event, state);
            case "skip" -> {
                if (state.current == null) {
                    event.reply("Nothing is playing right now.").setEphemeral(true).queue();
                } else {
                    skip(guildId, state);
                    event.reply("⏭️ Skipped.").queue();
                }
            }
            case "pause" -> link.updatePlayer(p -> p.setPaused(true)).subscribe(
                    p -> event.reply("⏸️ Paused.").queue(),
                    e -> event.reply("I couldn't pause playback. Is a song playing?").setEphemeral(true).queue()
            );
            case "resume" -> link.updatePlayer(p -> p.setPaused(false)).subscribe(
                    p -> event.reply("▶️ Resumed.").queue(),
                    e -> event.reply("I couldn't resume playback.").setEphemeral(true).queue()
            );
            case "stop" -> {
                synchronized (state) {
                    state.queue.clear();
                    state.current = null;
                }
                link.updatePlayer(PlayerUpdateBuilder::stopTrack).subscribe(
                        p -> {
                            if (!state.stayConnected) disconnect(event.getGuild());
                            event.reply("⏹️ Playback stopped and the queue was cleared.").queue();
                        },
                        e -> event.reply("The player could not be stopped.").setEphemeral(true).queue()
                );
            }
            case "loop" -> {
                String mode = event.getOption("mode").getAsString();
                synchronized (state) { state.loopMode = mode; }
                event.reply("🔁 Loop mode: **" + mode + "**").queue();
            }
            case "volume" -> {
                long raw = event.getOption("percent").getAsLong();
                if (raw < 0 || raw > 100) {
                    event.reply("Choose a volume from 0 to 100.").setEphemeral(true).queue();
                    return;
                }
                link.updatePlayer(p -> p.setVolume((int) raw)).subscribe(
                        p -> event.reply("🔊 Volume set to **" + raw + "%**.").queue(),
                        e -> event.reply("I couldn't change the volume.").setEphemeral(true).queue()
                );
            }
            case "nowplaying" -> {
                if (state.current == null) {
                    event.reply("Nothing is playing right now.").setEphemeral(true).queue();
                } else {
                    event.replyEmbeds(nowPlayingEmbed(state))
                            .addActionRow(playerButtons()).queue();
                }
            }
            case "247" -> {
                boolean enabled = event.getOption("enabled").getAsBoolean();
                synchronized (state) { state.stayConnected = enabled; }
                event.reply(enabled
                        ? "♾️ 24/7 mode enabled. I'll stay in the voice channel when the queue is empty."
                        : "24/7 mode disabled. I'll leave after the queue finishes.")
                        .queue();
                if (!enabled && state.current == null && state.queue.isEmpty()) {
                    disconnect(event.getGuild());
                }
            }
            default -> event.reply("Unknown command.").setEphemeral(true).queue();
        }
    }

    private void play(SlashCommandInteractionEvent event, GuildMusic state, Link link) {
        Member member = event.getMember();
        if (member == null || member.getVoiceState() == null
                || !member.getVoiceState().inAudioChannel()) {
            event.reply("Join a voice channel first, then use `/play`.").setEphemeral(true).queue();
            return;
        }

        AudioChannel voiceChannel = member.getVoiceState().getChannel();
        if (voiceChannel == null) {
            event.reply("I couldn't find your voice channel.").setEphemeral(true).queue();
            return;
        }

        AudioChannel botChannel = event.getGuild().getSelfMember().getVoiceState().getChannel();
        if (botChannel != null && !botChannel.getId().equals(voiceChannel.getId())) {
            event.reply("I'm already playing in another voice channel in this server.")
                    .setEphemeral(true).queue();
            return;
        }

        if (botChannel == null) {
            event.getJDA().getDirectAudioController().connect(voiceChannel);
        }

        String query = event.getOption("query").getAsString().trim();
        String identifier = query.startsWith("http://") || query.startsWith("https://")
                ? query : "scsearch:" + query;

        event.deferReply().queue();

        link.loadItem(identifier).subscribe(new FunctionalLoadResultHandler(
                loaded -> {
                    addTrack(event, state, link, loaded.getTrack());
                },
                playlist -> {
                    int count = 0;
                    for (Track track : playlist.getTracks()) {
                        addTrackToQueue(event.getGuild().getIdLong(), state, link, track);
                        count++;
                    }
                    final int added = count;
                    event.getHook().sendMessage("📃 Added **" + added + "** tracks from the playlist.")
                            .queue();
                },
                search -> {
                    if (search.getTracks().isEmpty()) {
                        event.getHook().sendMessage("No matching tracks found.").queue();
                    } else {
                        addTrack(event, state, link, search.getTracks().get(0));
                    }
                },
                () -> event.getHook().sendMessage("No matching tracks found.").queue(),
                failed -> event.getHook().sendMessage("Couldn't load that track. Check the URL or try another search.")
                        .queue()
        ), error -> event.getHook().sendMessage("Music service error: " + error.getMessage()).queue());
    }

    private void addTrack(SlashCommandInteractionEvent event, GuildMusic state, Link link, Track track) {
        boolean startNow;
        synchronized (state) {
            startNow = state.current == null;
            if (startNow) state.current = track;
            else state.queue.addLast(track);
        }

        if (startNow) {
            link.updatePlayer(p -> p.setTrack(track).setPaused(false)).subscribe(
                    p -> event.getHook().sendMessage("🎶 Now playing: **" + track.getInfo().getTitle() + "**")
                            .queue(),
                    e -> event.getHook().sendMessage("I couldn't start that track.").queue()
            );
        } else {
            event.getHook().sendMessage("➕ Added to queue: **" + track.getInfo().getTitle() + "**")
                    .queue();
        }
    }

    private void addTrackToQueue(long guildId, GuildMusic state, Link link, Track track) {
        boolean startNow;
        synchronized (state) {
            startNow = state.current == null;
            if (startNow) state.current = track;
            else state.queue.addLast(track);
        }
        if (startNow) {
            link.updatePlayer(p -> p.setTrack(track).setPaused(false)).subscribe();
        }
    }

    private void advance(long guildId, GuildMusic state) {
        Track next = null;
        synchronized (state) {
            if (state.current != null && "track".equals(state.loopMode)) {
                next = state.current;
            } else {
                Track finished = state.current;
                if (finished != null && "queue".equals(state.loopMode)) {
                    state.queue.addLast(finished);
                }
                next = state.queue.pollFirst();
            }
            state.current = next;
        }

        Link link = lavalink.getOrCreateLink(guildId);
        if (next != null) {
            Track chosen = next;
            link.updatePlayer(p -> p.setTrack(chosen).setPaused(false)).subscribe();
        } else {
            link.updatePlayer(PlayerUpdateBuilder::stopTrack).subscribe();
            Guild guild = jda.getGuildById(guildId);
            if (guild != null && !state.stayConnected) disconnect(guild);
        }
    }

    private void skip(long guildId, GuildMusic state) {
        Track next;
        synchronized (state) {
            next = state.queue.pollFirst();
            state.current = next;
        }
        Link link = lavalink.getOrCreateLink(guildId);
        if (next == null) {
            link.updatePlayer(PlayerUpdateBuilder::stopTrack).subscribe();
            Guild guild = jda.getGuildById(guildId);
            if (guild != null && !state.stayConnected) disconnect(guild);
        } else {
            link.updatePlayer(p -> p.setTrack(next).setPaused(false)).subscribe();
        }
    }

    private void disconnect(Guild guild) {
        AudioChannel channel = guild.getSelfMember().getVoiceState().getChannel();
        if (channel != null) guild.getJDA().getDirectAudioController().disconnect(guild);
        GuildMusic state = music.get(guild.getIdLong());
        if (state != null) {
            synchronized (state) {
                state.current = null;
                state.queue.clear();
            }
        }
    }

    private void showQueue(SlashCommandInteractionEvent event, GuildMusic state) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(88, 101, 242))
                .setTitle("🎵 Music Queue");
        synchronized (state) {
            if (state.current != null) {
                embed.addField("Now playing", state.current.getInfo().getTitle(), false);
            }
            if (state.queue.isEmpty()) {
                embed.addField("Up next", "The queue is empty.", false);
            } else {
                StringBuilder list = new StringBuilder();
                int index = 1;
                for (Track track : state.queue) {
                    if (index > 10) break;
                    list.append("**").append(index++).append(".** ")
                            .append(track.getInfo().getTitle()).append("\n");
                }
                if (state.queue.size() > 10) {
                    list.append("\n…and ").append(state.queue.size() - 10).append(" more.");
                }
                embed.addField("Up next", list.toString(), false);
            }
            embed.setFooter("Loop: " + state.loopMode + " • 24/7: " + (state.stayConnected ? "On" : "Off"));
        }
        event.replyEmbeds(embed.build()).queue();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed nowPlayingEmbed(GuildMusic state) {
        synchronized (state) {
            return new EmbedBuilder()
                    .setColor(new Color(88, 101, 242))
                    .setTitle("🎶 Now Playing")
                    .setDescription(state.current == null ? "Nothing is playing."
                            : "**" + state.current.getInfo().getTitle() + "**")
                    .setFooter("Loop: " + state.loopMode + " • 24/7: " + (state.stayConnected ? "On" : "Off"))
                    .build();
        }
    }

    private Button[] playerButtons() {
        return new Button[] {
                Button.secondary("music:pause", "Pause"),
                Button.success("music:resume", "Resume"),
                Button.primary("music:skip", "Skip"),
                Button.danger("music:stop", "Stop")
        };
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null || !event.getComponentId().startsWith("music:")) return;
        long guildId = event.getGuild().getIdLong();
        GuildMusic state = state(guildId);
        Link link = lavalink.getOrCreateLink(guildId);

        switch (event.getComponentId()) {
            case "music:pause" -> link.updatePlayer(p -> p.setPaused(true)).subscribe(
                    p -> event.reply("⏸️ Paused.").setEphemeral(true).queue(),
                    e -> event.reply("Nothing is playing.").setEphemeral(true).queue()
            );
            case "music:resume" -> link.updatePlayer(p -> p.setPaused(false)).subscribe(
                    p -> event.reply("▶️ Resumed.").setEphemeral(true).queue(),
                    e -> event.reply("Nothing is playing.").setEphemeral(true).queue()
            );
            case "music:skip" -> {
                skip(guildId, state);
                event.reply("⏭️ Skipped.").setEphemeral(true).queue();
            }
            case "music:stop" -> {
                synchronized (state) {
                    state.queue.clear();
                    state.current = null;
                }
                link.updatePlayer(PlayerUpdateBuilder::stopTrack).subscribe();
                if (!state.stayConnected) disconnect(event.getGuild());
                event.reply("⏹️ Stopped and cleared the queue.").setEphemeral(true).queue();
            }
            default -> event.reply("Unknown control.").setEphemeral(true).queue();
        }
    }

    private static final class GuildMusic {
        private final Deque<Track> queue = new ArrayDeque<>();
        private Track current;
        private String loopMode = "off";
        private boolean stayConnected = false;
    }
}
