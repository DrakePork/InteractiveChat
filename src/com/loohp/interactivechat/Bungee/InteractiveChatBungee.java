package com.loohp.interactivechat.Bungee;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.loohp.interactivechat.Bungee.Metrics.Charts;
import com.loohp.interactivechat.Bungee.Metrics.Metrics;
import com.loohp.interactivechat.ObjectHolders.CustomPlaceholder;
import com.loohp.interactivechat.ObjectHolders.CustomPlaceholder.CustomPlaceholderClickEvent;
import com.loohp.interactivechat.ObjectHolders.CustomPlaceholder.CustomPlaceholderHoverEvent;
import com.loohp.interactivechat.ObjectHolders.CustomPlaceholder.CustomPlaceholderReplaceText;
import com.loohp.interactivechat.ObjectHolders.CustomPlaceholder.ParsePlayer;
import com.loohp.interactivechat.ObjectHolders.ICPlaceholder;
import com.loohp.interactivechat.Utils.CompressionUtils;
import com.loohp.interactivechat.Utils.CustomArrayUtils;
import com.loohp.interactivechat.Utils.DataTypeIO;
import com.loohp.interactivechat.Utils.MessageUtils;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.protocol.packet.Chat;

public class InteractiveChatBungee extends Plugin implements Listener {
	
	public static Configuration config = null;
	public static ConfigurationProvider yamlConfigProvider = null;
	public static File configFile;
	public static File playerDataFolder;

	public static Plugin plugin;
	public static Metrics metrics;
	private static Random random = new Random();
	public static AtomicLong pluginMessagesCounter = new AtomicLong(0);
	
	private Map<Integer, byte[]> incomming = new HashMap<>();
	
	private Map<UUID, List<String>> forwardedMessages = new ConcurrentHashMap<>(); 
	private Map<UUID, UUID> requestedMessages = new ConcurrentHashMap<>(); 
	
	private Map<UUID, List<UUID>> requestedMessageProcesses = new ConcurrentHashMap<>();
	
	public static List<String> parseCommands = new ArrayList<>();
	
	public static Map<String, Map<String, String>> aliasesMapping = new HashMap<>();
	public static Map<String, List<ICPlaceholder>> placeholderList = new HashMap<>();
	
	public static int delay = 200;

	@Override
	public void onEnable() {
		plugin = this;
		
		yamlConfigProvider = ConfigurationProvider.getProvider(YamlConfiguration.class);
		if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
		}
        configFile = new File(getDataFolder(), "bungeeconfig.yml");
        playerDataFolder = new File(getDataFolder(), "player_data");
        if (!playerDataFolder.exists()) {
        	playerDataFolder.mkdirs();
        }

        if (!configFile.exists()) {
            try (InputStream in = getResourceAsStream("bungeeconfig.yml")) {
                Files.copy(in, configFile.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        loadConfig();

		getProxy().registerChannel("interchat:main");
		getProxy().getPluginManager().registerListener(this, this);

		getProxy().getPluginManager().registerCommand(this, new Commands());

		getLogger().info(ChatColor.GREEN + "[InteractiveChat] Registered Plugin Messaging Channels!");

		metrics = new Metrics(plugin, 8839);
		Charts.setup(metrics);

		run();

		getLogger().info(ChatColor.GREEN + "[InteractiveChat] InteractiveChatBungee has been enabled!");
	}

	@Override
	public void onDisable() {
		getLogger().info(ChatColor.RED + "[InteractiveChat] InteractiveChatBungee has been disabled!");
	}
	
	public static void loadConfig() {
		try {
			config = yamlConfigProvider.load(configFile);
			parseCommands = config.getStringList("Settings.CommandsToParse");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void run() {
		new Timer().schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					sendPlayerListData();
					sendDelay();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}, 0, 10000);
	}

	@EventHandler
	public void onReceive(PluginMessageEvent event) {
		if (!event.getTag().equals("interchat:main")) {
			return;
		}
		
		event.setCancelled(true);

		SocketAddress senderServer = event.getSender().getSocketAddress();
		
		byte[] packet = Arrays.copyOf(event.getData(), event.getData().length);
		ByteArrayDataInput in = ByteStreams.newDataInput(packet);
		int packetNumber = in.readInt();
		int packetId = in.readShort();
		
		if (packetId == 0x08 || packetId == 0x09 || packetId == 0x10 || packetId == 0x11) {
			boolean isEnding = in.readBoolean();
	        byte[] data = new byte[packet.length - 7];
	        in.readFully(data);
	        
	        byte[] chain = incomming.remove(packetNumber);
	    	if (chain != null) {
	    		ByteBuffer buff = ByteBuffer.allocate(chain.length + data.length);
	    		buff.put(chain);
	    		buff.put(data);
	    		data = buff.array();
	    	}
	        
	        if (!isEnding) {
	        	incomming.put(packetNumber, data);
	        	return;
	        }
	        
	        try {
	        	ByteArrayDataInput input = ByteStreams.newDataInput(CompressionUtils.decompress(data));	        	
		        switch (packetId) {
		        case 0x08:
		        	UUID messageId = DataTypeIO.readUUID(input);
		        	String component = DataTypeIO.readString(input, StandardCharsets.UTF_8);
		        	UUID playerUUID = requestedMessages.get(messageId);
		        	List<UUID> messageQueue = requestedMessageProcesses.get(playerUUID);
		        	
		        	//ProxyServer.getInstance().getConsole().sendMessage(new TextComponent(messageId.toString() + " <- " + component));
		        	
		        	if (playerUUID != null && messageQueue != null) {
		        		new Thread(new Runnable() {
		        			@Override
		        			public void run() {
				        		CompletableFuture<Void> future = new CompletableFuture<Void>();
				        		new Thread(new Runnable() {
				        			@Override
				        			public void run() {
				        				while (true) {
				        					if (messageQueue.indexOf(messageId) == 0) {
				        						future.complete(null);
				        						break;
				        					}
				        					if (future.isDone()) {
				        						break;
				        					}
				        					try {
												TimeUnit.MILLISECONDS.sleep(10);
											} catch (InterruptedException e) {
												e.printStackTrace();
											}
				        				}
				        			}
				        		}).start();
				        		
				        		try {
									future.get(delay + 2000, TimeUnit.MILLISECONDS);
								} catch (InterruptedException | ExecutionException | TimeoutException e) {}
				        		if (!future.isDone()) {
				        			future.complete(null);
	        					}			     
				        		
				        		Chat chatPacket = new Chat(component + "<QUxSRUFEWVBST0NFU1NFRA==>");
				        		UserConnection userConnection = (UserConnection) getProxy().getPlayer(playerUUID);
				        		ChannelWrapper channelWrapper;
				        		Field channelField = null;

				        		try {
				        			channelField = userConnection.getClass().getDeclaredField("ch");
				        			channelField.setAccessible(true);
				        			channelWrapper = (ChannelWrapper) channelField.get(userConnection);
				        		} catch (NoSuchFieldException | IllegalAccessException e) {
				        			throw new RuntimeException(e);
				        		} finally {
				        			if (channelField != null) {
				        				channelField.setAccessible(false);
				        			}
				        		}
				        		
				        		messageQueue.remove(messageId);
				        		channelWrapper.write(chatPacket);
		        			}
		        		}).start();
		        	}
		        	break;
		        case 0x09:
		        	loadConfig();
		        	break;
		        case 0x10:
		        	int size = input.readInt();
		        	Map<String, String> map = new HashMap<>();
		        	for (int i = 0; i < size; i++) {
		        		String key = DataTypeIO.readString(input, StandardCharsets.UTF_8);
		        		String value = DataTypeIO.readString(input, StandardCharsets.UTF_8);
		        		map.put(key, value);
		        	}
		        	aliasesMapping.put(((Server) event.getSender()).getInfo().getName(), map);
		        	break;
		        case 0x11:
		        	int size1 = input.readInt();
		        	List<ICPlaceholder> list = new ArrayList<>(size1);
		        	for (int i = 0; i < size1; i++) {
		        		boolean isBulitIn = input.readBoolean();
		        		if (isBulitIn) {
		        			list.add(new ICPlaceholder(DataTypeIO.readString(input, StandardCharsets.UTF_8), input.readBoolean()));
		        		} else {
		        			int customNo = input.readInt();
		        			ParsePlayer parseplayer = ParsePlayer.fromOrder(input.readByte());	
		        			String placeholder = DataTypeIO.readString(input, StandardCharsets.UTF_8);
		        			List<String> aliases = new ArrayList<>();
		        			int aliasSize = input.readInt();
		        			for (int u = 0; u < aliasSize; u++) {
		        				aliases.add(DataTypeIO.readString(input, StandardCharsets.UTF_8));
		        			}
		        			boolean parseKeyword = input.readBoolean();
		        			boolean casesensitive = input.readBoolean();
		        			long cooldown = input.readLong();
		        			boolean hoverEnabled = input.readBoolean();
		        			String hoverText = DataTypeIO.readString(input, StandardCharsets.UTF_8);
		        			boolean clickEnabled = input.readBoolean();
		        			String clickAction = DataTypeIO.readString(input, StandardCharsets.UTF_8);
		        			String clickValue = DataTypeIO.readString(input, StandardCharsets.UTF_8);
		        			boolean replaceEnabled = input.readBoolean();
		        			String replaceText = DataTypeIO.readString(input, StandardCharsets.UTF_8);

		        			list.add(new CustomPlaceholder(customNo, parseplayer, placeholder, aliases, parseKeyword, casesensitive, cooldown, new CustomPlaceholderHoverEvent(hoverEnabled, hoverText), new CustomPlaceholderClickEvent(clickEnabled, clickEnabled ? ClickEvent.Action.valueOf(clickAction) : null, clickValue), new CustomPlaceholderReplaceText(replaceEnabled, replaceText)));
		        		}
		        	}
		        	placeholderList.put(((Server) event.getSender()).getInfo().getName(), list);
		        	forwardPlaceholderList(list, ((Server) event.getSender()).getInfo());
		        	break;
		        case 0x12:
		        	UUID uuid2 = DataTypeIO.readUUID(input);
		        	String playerdata = DataTypeIO.readString(input, StandardCharsets.UTF_8);
		        	Configuration playerconfig = yamlConfigProvider.load(playerdata);
		        	yamlConfigProvider.save(playerconfig, new File(playerDataFolder, uuid2.toString()));
		        	forwardPlayerData(uuid2, playerdata, ((Server) event.getSender()).getInfo());
		        	break;
		        }
	        } catch (IOException | DataFormatException e) {
				e.printStackTrace();
			}
		} else {
			for (ServerInfo server : getProxy().getServers().values()) {
				if (!server.getSocketAddress().equals(senderServer) && server.getPlayers().size() > 0) {
					server.sendData("interchat:main", event.getData());
					pluginMessagesCounter.incrementAndGet();
				}
			}
		}
	}
	
	private void forwardPlayerData(UUID uuid, String playerdata, ServerInfo serverFrom) throws IOException {
		ByteArrayDataOutput output = ByteStreams.newDataOutput();

		DataTypeIO.writeUUID(output, uuid);
    	DataTypeIO.writeString(output, playerdata, StandardCharsets.UTF_8);

		int packetNumber = random.nextInt();
		int packetId = 0x12;
		byte[] data = output.toByteArray();

		byte[][] dataArray = CustomArrayUtils.divideArray(CompressionUtils.compress(data), 32700);

		for (int i = 0; i < dataArray.length; i++) {
			byte[] chunk = dataArray[i];

			ByteArrayDataOutput out = ByteStreams.newDataOutput();
			out.writeInt(packetNumber);

			out.writeShort(packetId);
			out.writeBoolean(i == (dataArray.length - 1));

			out.write(chunk);

			for (ServerInfo server : getProxy().getServers().values()) {
				if (!server.getSocketAddress().equals(serverFrom.getSocketAddress())) {
					server.sendData("interchat:main", out.toByteArray());
					pluginMessagesCounter.incrementAndGet();
				}
			}
		}
	}
	
	private void forwardPlaceholderList(List<ICPlaceholder> serverPlaceholderList, ServerInfo serverFrom) throws IOException {
		ByteArrayDataOutput output = ByteStreams.newDataOutput();

		DataTypeIO.writeString(output, serverFrom.getName(), StandardCharsets.UTF_8);
		output.writeInt(serverPlaceholderList.size());
    	for (ICPlaceholder placeholder : serverPlaceholderList) {
    		boolean isBuiltIn = placeholder.isBuildIn();
    		output.writeBoolean(isBuiltIn);
    		if (isBuiltIn) {
    			DataTypeIO.writeString(output, placeholder.getKeyword(), StandardCharsets.UTF_8);
    			output.writeBoolean(placeholder.isCaseSensitive());
    		} else {
    			CustomPlaceholder customPlaceholder = placeholder.getCustomPlaceholder().get();
    			output.writeInt(customPlaceholder.getPosition());
    			output.writeByte(customPlaceholder.getParsePlayer().getOrder());
    			DataTypeIO.writeString(output, customPlaceholder.getKeyword(), StandardCharsets.UTF_8);
    			output.writeInt(customPlaceholder.getAliases().size());
    			for (String each : customPlaceholder.getAliases()) {
    				DataTypeIO.writeString(output, each, StandardCharsets.UTF_8);
    			}
    			output.writeBoolean(customPlaceholder.getParseKeyword());
    			output.writeBoolean(customPlaceholder.isCaseSensitive());
    			output.writeLong(customPlaceholder.getCooldown());
    			
    			CustomPlaceholderHoverEvent hover = customPlaceholder.getHover();
    			output.writeBoolean(hover.isEnabled());
    			DataTypeIO.writeString(output, hover.getText(), StandardCharsets.UTF_8);
    			
    			CustomPlaceholderClickEvent click = customPlaceholder.getClick();
    			output.writeBoolean(click.isEnabled());
    			DataTypeIO.writeString(output, click.getAction() == null ? "" : click.getAction().name(), StandardCharsets.UTF_8);
    			DataTypeIO.writeString(output, click.getValue(), StandardCharsets.UTF_8);
    			
    			CustomPlaceholderReplaceText replace = customPlaceholder.getReplace();
    			output.writeBoolean(replace.isEnabled());
    			DataTypeIO.writeString(output, replace.getReplaceText(), StandardCharsets.UTF_8);
    		}
    	}

		int packetNumber = random.nextInt();
		int packetId = 0x09;
		byte[] data = output.toByteArray();

		byte[][] dataArray = CustomArrayUtils.divideArray(CompressionUtils.compress(data), 32700);

		for (int i = 0; i < dataArray.length; i++) {
			byte[] chunk = dataArray[i];

			ByteArrayDataOutput out = ByteStreams.newDataOutput();
			out.writeInt(packetNumber);

			out.writeShort(packetId);
			out.writeBoolean(i == (dataArray.length - 1));

			out.write(chunk);

			for (ServerInfo server : getProxy().getServers().values()) {
				if (!server.getSocketAddress().equals(serverFrom.getSocketAddress())) {
					server.sendData("interchat:main", out.toByteArray());
					pluginMessagesCounter.incrementAndGet();
				}
			}
		}
	}
	
	@EventHandler
	public void onBungeeChat(ChatEvent event) {
		ProxiedPlayer player = (ProxiedPlayer) event.getSender();
		UUID uuid = player.getUniqueId();
		String message = event.getMessage();
		
		Map<String, String> serverAliasesMapping = aliasesMapping.get(player.getServer().getInfo().getName());
		List<ICPlaceholder> serverPlaceholderList = placeholderList.get(player.getServer().getInfo().getName());
		if (serverAliasesMapping != null && serverPlaceholderList != null) {
			if (message.startsWith("/")) {
				if (InteractiveChatBungee.parseCommands.stream().anyMatch(each -> event.getMessage().matches(each))) {
					message = MessageUtils.preprocessMessage(message, serverPlaceholderList, serverAliasesMapping);
				}
			} else {
				message = MessageUtils.preprocessMessage(message, serverPlaceholderList, serverAliasesMapping);
			}
			event.setMessage(message);
		}
		
		String newMessage = event.getMessage();
		
		if (newMessage.startsWith("/")) {
			for (String parsecommand : InteractiveChatBungee.parseCommands) {
				//getProxy().getConsole().sendMessage(new TextComponent(parsecommand));
				if (newMessage.matches(parsecommand)) {
					String command = newMessage.trim();
					String uuidmatch = "<" + UUID.randomUUID().toString() + ">";
					command += uuidmatch;
					event.setMessage(command);
					try {
						sendCommandMatch(uuid, "", uuidmatch);
					} catch (IOException e) {
						e.printStackTrace();
					}
					break;
				}
			}
		} else {
			new Timer().schedule(new TimerTask() {
				@Override
				public void run() {
					List<String> messages = forwardedMessages.get(uuid);
					if (!messages.remove(newMessage)) {
						try {
							sendMessagePair(uuid, newMessage);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}, 100);
		}
	}
	
	private void sendCommandMatch(UUID uuid, String placeholder, String uuidmatch) throws IOException {
		ByteArrayDataOutput output = ByteStreams.newDataOutput();

		DataTypeIO.writeUUID(output, uuid);
    	DataTypeIO.writeString(output, placeholder, StandardCharsets.UTF_8);
    	DataTypeIO.writeString(output, uuidmatch, StandardCharsets.UTF_8);

		int packetNumber = random.nextInt();
		int packetId = 0x07;
		byte[] data = output.toByteArray();

		byte[][] dataArray = CustomArrayUtils.divideArray(CompressionUtils.compress(data), 32700);

		for (int i = 0; i < dataArray.length; i++) {
			byte[] chunk = dataArray[i];

			ByteArrayDataOutput out = ByteStreams.newDataOutput();
			out.writeInt(packetNumber);

			out.writeShort(packetId);
			out.writeBoolean(i == (dataArray.length - 1));

			out.write(chunk);

			for (ServerInfo server : getProxy().getServers().values()) {
				server.sendData("interchat:main", out.toByteArray());
				pluginMessagesCounter.incrementAndGet();
			}
		}
	}
	
	private void sendMessagePair(UUID uuid, String message) throws IOException {
		ByteArrayDataOutput output = ByteStreams.newDataOutput();

		DataTypeIO.writeString(output, message, StandardCharsets.UTF_8);
    	DataTypeIO.writeUUID(output, uuid);

		int packetNumber = random.nextInt();
		int packetId = 0x06;
		byte[] data = output.toByteArray();

		byte[][] dataArray = CustomArrayUtils.divideArray(CompressionUtils.compress(data), 32700);

		for (int i = 0; i < dataArray.length; i++) {
			byte[] chunk = dataArray[i];

			ByteArrayDataOutput out = ByteStreams.newDataOutput();
			out.writeInt(packetNumber);

			out.writeShort(packetId);
			out.writeBoolean(i == (dataArray.length - 1));

			out.write(chunk);

			for (ServerInfo server : getProxy().getServers().values()) {
				server.sendData("interchat:main", out.toByteArray());
				pluginMessagesCounter.incrementAndGet();
			}
		}
	}
	
	@EventHandler
	public void onServerConnected(ServerConnectedEvent event) {
		ProxiedPlayer player = event.getPlayer();
		
		ServerConnection serverConnection = (ServerConnection) event.getServer();
		ChannelWrapper channelWrapper;
		Field channelField = null;

		try {
			channelField = serverConnection.getClass().getDeclaredField("ch");
			channelField.setAccessible(true);
			channelWrapper = (ChannelWrapper) channelField.get(serverConnection);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new RuntimeException(e);
		} finally {
			if (channelField != null) {
				channelField.setAccessible(false);
			}
		}

		ChannelPipeline pipeline = channelWrapper.getHandle().pipeline();

		pipeline.addBefore(PipelineUtils.BOSS_HANDLER, "packet_interceptor", new ChannelDuplexHandler() {
			@Override
			public void write(ChannelHandlerContext channelHandlerContext, Object obj, ChannelPromise channelPromise) throws Exception {
				if (obj instanceof Chat) {
					Chat packet = (Chat) obj;
					forwardedMessages.get(player.getUniqueId()).add(packet.getMessage());
				}
				super.write(channelHandlerContext, obj, channelPromise); // send it to client
			}
		});
	}

	@EventHandler
	public void onPlayerConnected(PostLoginEvent event) {
		ProxiedPlayer player = event.getPlayer();
		UUID uuid = player.getUniqueId();
		forwardedMessages.put(player.getUniqueId(), new ArrayList<>());
		List<UUID> messageQueue = Collections.synchronizedList(new LinkedList<>());
		requestedMessageProcesses.put(player.getUniqueId(), messageQueue);
		
		File playerFile = new File(playerDataFolder, uuid.toString());
		if (playerFile.exists()) {
			try {
				Configuration playerconfig = yamlConfigProvider.load(playerFile);
				StringWriter writer = new StringWriter();
				yamlConfigProvider.save(playerconfig, writer);
				forwardPlayerData(uuid, writer.toString(), player.getServer().getInfo());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		UserConnection userConnection = (UserConnection) player;
		ChannelWrapper channelWrapper;
		Field channelField = null;

		try {
			channelField = userConnection.getClass().getDeclaredField("ch");
			channelField.setAccessible(true);
			channelWrapper = (ChannelWrapper) channelField.get(userConnection);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new RuntimeException(e);
		} finally {
			if (channelField != null) {
				channelField.setAccessible(false);
			}
		}

		ChannelPipeline pipeline = channelWrapper.getHandle().pipeline();

		pipeline.addBefore(PipelineUtils.BOSS_HANDLER, "packet_interceptor", new ChannelDuplexHandler() {
			@Override
			public void write(ChannelHandlerContext channelHandlerContext, Object obj, ChannelPromise channelPromise) throws Exception {
				if (obj instanceof Chat) {
					Chat packet = (Chat) obj;
					if (packet.getMessage().contains("<QUxSRUFEWVBST0NFU1NFRA==>")) {
						packet.setMessage(packet.getMessage().replace("<QUxSRUFEWVBST0NFU1NFRA==>", ""));
					} else if (player.getServer() != null) {
						UUID messageId = UUID.randomUUID();
						messageQueue.add(messageId);
						//ProxyServer.getInstance().getConsole().sendMessage(new TextComponent(messageId.toString() + " -> " + packet.getMessage()));
						new Timer().schedule(new TimerTask() {
							@Override
							public void run() {
								try {
									requestMessageProcess(player, packet.getMessage(), messageId);
								} catch (IOException e) {
									e.printStackTrace();
								}
							}
						}, delay + 50);
						return;
					}
				}
				super.write(channelHandlerContext, obj, channelPromise); // send it to client
			}
		});
	}
	
	private void requestMessageProcess(ProxiedPlayer player, String component, UUID messageId) throws IOException {
		ByteArrayDataOutput output = ByteStreams.newDataOutput();
		
		DataTypeIO.writeUUID(output, messageId);
		DataTypeIO.writeUUID(output, player.getUniqueId());
		DataTypeIO.writeString(output, component, StandardCharsets.UTF_8);

		ServerInfo server = player.getServer().getInfo();

		int packetNumber = random.nextInt();
		int packetId = 0x08;
		byte[] data = output.toByteArray();

		byte[][] dataArray = CustomArrayUtils.divideArray(CompressionUtils.compress(data), 32700);

		for (int i = 0; i < dataArray.length; i++) {
			byte[] chunk = dataArray[i];

			ByteArrayDataOutput out = ByteStreams.newDataOutput();
			out.writeInt(packetNumber);

			out.writeShort(packetId);
			out.writeBoolean(i == (dataArray.length - 1));

			out.write(chunk);

			server.sendData("interchat:main", out.toByteArray());
			pluginMessagesCounter.incrementAndGet();
		}
		
		requestedMessages.put(messageId, player.getUniqueId());
	}

	@EventHandler
	public void onSwitch(ServerSwitchEvent event) {
		ServerInfo to = event.getPlayer().getServer().getInfo();
		if (!placeholderList.containsKey(to.getName())) {
			try {
				requestPlaceholderList(to);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		if (!aliasesMapping.containsKey(to.getName())) {
			try {
				requestAliasesMapping(to);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		try {
			sendPlayerListData();
		} catch (IOException e) {
			e.printStackTrace();
		}
		new Timer().schedule(new TimerTask() {
			@Override
			public void run() {
				if (event.getPlayer().getName().equals("LOOHP") || event.getPlayer().getName().equals("AppLEskakE")) {
					event.getPlayer().sendMessage(new TextComponent(ChatColor.GOLD + "InteractiveChat (Bungeecord) " + plugin.getDescription().getVersion() + " is running!"));
				}
			}
		}, 200);
	}
	
	private void requestPlaceholderList(ServerInfo server) throws IOException {
		ByteArrayDataOutput output = ByteStreams.newDataOutput();

		int packetNumber = random.nextInt();
		int packetId = 0x10;
		byte[] data = output.toByteArray();

		byte[][] dataArray = CustomArrayUtils.divideArray(CompressionUtils.compress(data), 32700);

		for (int i = 0; i < dataArray.length; i++) {
			byte[] chunk = dataArray[i];

			ByteArrayDataOutput out = ByteStreams.newDataOutput();
			out.writeInt(packetNumber);

			out.writeShort(packetId);
			out.writeBoolean(i == (dataArray.length - 1));

			out.write(chunk);

			server.sendData("interchat:main", out.toByteArray());
			pluginMessagesCounter.incrementAndGet();
		}
	}
	
	private void requestAliasesMapping(ServerInfo server) throws IOException {
		ByteArrayDataOutput output = ByteStreams.newDataOutput();

		int packetNumber = random.nextInt();
		int packetId = 0x11;
		byte[] data = output.toByteArray();

		byte[][] dataArray = CustomArrayUtils.divideArray(CompressionUtils.compress(data), 32700);

		for (int i = 0; i < dataArray.length; i++) {
			byte[] chunk = dataArray[i];

			ByteArrayDataOutput out = ByteStreams.newDataOutput();
			out.writeInt(packetNumber);

			out.writeShort(packetId);
			out.writeBoolean(i == (dataArray.length - 1));

			out.write(chunk);

			server.sendData("interchat:main", out.toByteArray());
			pluginMessagesCounter.incrementAndGet();
		}
	}

	@EventHandler
	public void onLeave(PlayerDisconnectEvent event) {
		forwardedMessages.remove(event.getPlayer().getUniqueId());
		requestedMessageProcesses.remove(event.getPlayer().getUniqueId());
		new Timer().schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					sendPlayerListData();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}, 1000);
	}

	private void sendPlayerListData() throws IOException {
		ByteArrayDataOutput output = ByteStreams.newDataOutput();
		Collection<ProxiedPlayer> players = ProxyServer.getInstance().getPlayers();
		output.writeInt(players.size());
		for (ProxiedPlayer player : players) {
			if (player.getServer() != null) {
				DataTypeIO.writeString(output, player.getServer().getInfo().getName(), StandardCharsets.UTF_8);
				DataTypeIO.writeUUID(output, player.getUniqueId());
				DataTypeIO.writeString(output, player.getDisplayName(), StandardCharsets.UTF_8);
			}
		}

		int packetNumber = random.nextInt();
		int packetId = 0x00;
		byte[] data = output.toByteArray();

		byte[][] dataArray = CustomArrayUtils.divideArray(CompressionUtils.compress(data), 32700);

		for (int i = 0; i < dataArray.length; i++) {
			byte[] chunk = dataArray[i];

			ByteArrayDataOutput out = ByteStreams.newDataOutput();
			out.writeInt(packetNumber);

			out.writeShort(packetId);
			out.writeBoolean(i == (dataArray.length - 1));

			out.write(chunk);

			for (ServerInfo server : getProxy().getServers().values()) {
				server.sendData("interchat:main", out.toByteArray());
				pluginMessagesCounter.incrementAndGet();
			}
		}
	}

	private void sendDelay() throws IOException {
		ByteArrayDataOutput output = ByteStreams.newDataOutput();

		List<CompletableFuture<Integer>> futures = new LinkedList<>();

		for (ServerInfo server : getProxy().getServers().values()) {
			futures.add(getPing(server));
		}
		int highestPing = futures.stream().mapToInt(each -> {
			try {
				return each.get();
			} catch (InterruptedException | ExecutionException e) {
				return 0;
			}
		}).max().orElse(0);
		
		delay = highestPing * 2 + 100;

		output.writeInt(delay);

		int packetNumber = random.nextInt();
		int packetId = 0x01;
		byte[] data = output.toByteArray();

		byte[][] dataArray = CustomArrayUtils.divideArray(CompressionUtils.compress(data), 32700);

		for (int i = 0; i < dataArray.length; i++) {
			byte[] chunk = dataArray[i];

			ByteArrayDataOutput out = ByteStreams.newDataOutput();
			out.writeInt(packetNumber);

			out.writeShort(packetId);
			out.writeBoolean(i == (dataArray.length - 1));

			out.write(chunk);

			for (ServerInfo server : getProxy().getServers().values()) {
				server.sendData("interchat:main", out.toByteArray());
				pluginMessagesCounter.incrementAndGet();
			}
		}
	}

	private CompletableFuture<Integer> getPing(ServerInfo server) {
		CompletableFuture<Integer> future = new CompletableFuture<>();
		long start = System.currentTimeMillis();
		Callback<ServerPing> callback = new Callback<ServerPing>() {
			@Override
			public void done(ServerPing result, Throwable error) {
				if (error == null) {
					future.complete((int) (System.currentTimeMillis() - start));
				} else {
					future.complete(0);
				}
			}
		};
		server.ping(callback);
		return future;
	}
}