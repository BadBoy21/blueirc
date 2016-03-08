package tk.microdroid.blueirc;

import java.io.IOException;

public class Handler {

	/**
	 * Handle incoming IRC messages. Here you simply handle all the command and
	 * Messages sent from the IRC server, Worker variables Are now changed to
	 * have package visibility, so you Can access the variables of Worker
	 * through the passed w Parameter.
	 * 
	 * This had better be in Worker class itself, but that way the Worker class
	 * grown to an abnormal length, so this splits Things in about a half.
	 * 
	 * @param w
	 *            The worker instance
	 * @param p
	 *            The parser instance, the input from the server boxed in a
	 *            Parser
	 * @throws IOException
	 */
	static void handle(Worker w, Parser p) throws IOException {
		switch (p.action) {
		case "PING":
			w.send("PONG" + w.io.line.substring(4));
			break;
		case "PONG": // For lag measurement
			if (p.msg.equals(w.lagPrefix + w.lagPingId)) {
				w.finishedLagMeasurement = true;
				w.lag = System.currentTimeMillis() - w.lagStart;
				w.eventHandler.onEvent(Event.LAG_MEASURED, w.lag);
			} else if (p.msg.equals(w.userLagPrefix + w.userLagPingId)) {
				w.finishedUserLagMeasurement = true;
				w.eventHandler.onEvent(Event.USER_LAG_MEASURED,
						System.currentTimeMillis() - w.userLagStart);
			}
		case "PRIVMSG": // Add the message to the chan/user
			if (p.actionArgs.get(0).matches("[\\#\\&].+")) {
				Channel chan = w.chans.get(p.actionArgs.get(0));
				chan.getUsers().get(p.nick).addMessage(p);
				chan.addMessage(p);
			} else {
				if (w.users.containsKey(p.actionArgs.get(0))) {
					w.users.get(p.actionArgs.get(0)).addMessage(p);
				} else {
					User user = new User(p.actionArgs.get(0), "");
					user.addMessage(p);
				}
			}
			break;
		case "CAP":
			w.ircv3Support = true;
			String capType = p.actionArgs.get(1);
			if (capType.equals("LS")) {
				w.ircv3Capabilities = p.msg.split(" ");
				String[] reqCpbs = { "multi-prefix" }; // Requested capabilities
				for (String reqCpb : reqCpbs)
					if (w.hasCapability(reqCpb))
						w.send("CAP REQ " + reqCpb);
				w.send("CAP END");
				w.register(w.serverInfo.nick);
			} else if (capType.equals("NAK"))
				w.eventHandler.onEvent(Event.IRCV3_CAPABILITY_REJECTED, p.msg);
			else if (capType.equals("ACK"))
				w.eventHandler.onEvent(Event.IRCV3_CAPABILITY_ACCEPTED, p.msg);
			break;
		case "JOIN": // Create new Channel
			if (p.nick.equals(w.usingSecondNick ? w.serverInfo.secondNick
					: w.serverInfo.nick)) {
				if (!w.chans.containsKey(p.msg)) {
					w.chans.put(p.msg, new Channel(p.msg));
				} else {
					w.chans.get(p.msg).rejoin();
				}
			} else {
				if (w.chans.containsKey(p.msg)) {
					Channel chan = w.chans.get(p.msg);
					if (!chan.hasUser(p.nick))
						chan.addUser(p.nick, null);
				}
			}
			break;
		case "PART": // Remove channel (When not preserving them) or user in
						// channel
			if (p.nick.equals(w.usingSecondNick ? w.serverInfo.secondNick
					: w.serverInfo.nick)) {
				w.eventHandler.onEvent(Event.LEFT_CHANNEL, p.actionArgs.get(0));
				w.chans.get(p.actionArgs.get(0)).leave();
				if (!w.preserveChannels)
					w.chans.remove(p.actionArgs.get(0));
			} else {
				w.chans.get(p.actionArgs.get(0)).removeUser(p.nick);
				if (!w.preserveUsers) {
					boolean userStillVisible = false; // i.e. We can see the
														// user somewhere in the
														// other channels
					for (Channel chan : w.chans.values())
						userStillVisible = chan.hasUser(p.nick)
								|| userStillVisible;
					if (!userStillVisible)
						w.users.remove(p.nick);
				}
			}
			break;
		case "NICK": // Update nicknames upon nicknames change
			if (p.msg.equals(w.serverInfo.nick))
				w.serverInfo.nick = p.msg;
			for (Channel chan : w.chans.values())
				for (User user : chan.getUsers().values())
					if (user.getNick().equals(p.nick))
						user.updateNick(p.msg);
			if (w.users.containsKey(p.nick))
				w.users.get(p.nick).updateNick(p.msg);
			break;
		case "KICK": // Remove channel (If not preserving them) or user in
						// channel
			if (p.actionArgs.get(1).equals(
					w.usingSecondNick ? w.serverInfo.secondNick
							: w.serverInfo.nick)) {
				w.eventHandler.onEvent(Event.KICKED, p);
				w.chans.get(p.actionArgs.get(0)).leave();
				if (!w.preserveChannels)
					w.chans.remove(p.actionArgs.get(0));
			} else {
				w.chans.get(p.actionArgs.get(0))
						.removeUser(p.actionArgs.get(1));
				if (!w.preserveUsers) {
					boolean userStillVisible = false; // i.e. We can see the
														// user somewhere in the
														// other channels
					for (Channel chan : w.chans.values())
						userStillVisible = chan.hasUser(p.nick)
								|| userStillVisible;
					if (!userStillVisible)
						w.users.remove(p.nick);
				}
			}
			break;
		case "QUIT": // Remove user from all the channels
			if (!w.preserveUsers) {
				for (Channel chan : w.chans.values())
					chan.removeUser(p.nick);
				if (w.users.containsKey(p.nick))
					w.users.remove(p.nick);
			}
		case "TOPIC": // Update topic upon change
			if (w.chans.containsKey(p.actionArgs.get(0)))
				w.chans.get(p.actionArgs.get(0)).setTopic(p.msg);
			break;
		case "NOTICE":
			if (p.msg.toLowerCase().equals("*** no ident response") && !w.connected)
				w.register(w.serverInfo.nick);
			break;
		default:
			switch (p.numberAction) {
			case "352": // Parse WHO response
				//Sample WHO response:
				//:leguin.freenode.net 352 BlueIRCNick #blueirc-test ~GitGud unaffiliated/gitgud rajaniemi.freenode.net GitGud H@ :0 Lowlife
				String ident = null;
				String hostmask = null;
				String nick = null;
				String server = null;
				String realname = "";
				String channel = null;
				realname = p.raw.split(":0 ")[1];
				ident = p.raw.split(" ")[4];
				channel = p.raw.split(" ")[3];
				hostmask = p.raw.split(" ")[5];
				nick = p.raw.split(" ")[7];
				server = p.raw.split(" ")[6];
				realname = p.raw.split(":0 ")[1];
				// After getting the informations it goes and finds the relevant User by nick (which was already established from NAMES)
				// And adds the remaining informations on there
				if (w.chans.containsKey(channel)) {
					Channel chan = w.chans.get(channel);
					User chanUser = chan.getUsers().get(nick);
					chanUser.setServer(server);
					chanUser.setHostname(hostmask);
					chanUser.setRealName(realname);
					chanUser.setLogin(ident);
					System.out.println(chanUser.toString());
				}
				
				break;
			case "332": // The topic sent upon join
				if (w.chans.containsKey(p.actionArgs.get(1)))
					w.chans.get(p.actionArgs.get(1)).setTopic(p.msg);
				break;
			case "001": // Welcome to the server
				w.eventHandler.onEvent(Event.CONNECTED, p.server);
				w.lagTimer.schedule(w.new LagPing(), 0, 30000);
				w.connected = true;
				break;
			case "421": // Unknown command CAP (i.e. the server doesn't support
						// IRCv3)
			case "451": // You have not registered
				if (!w.connected)
					w.register(w.serverInfo.nick);
				break;
			case "353": // NAMES response
				if (w.chans.containsKey(p.actionArgs.get(2))) {
					Channel chan = w.chans.get(p.actionArgs.get(2));
					for (String user : p.msg.split(" ")) {
						chan.addUser(user, w.prefixes);
						if (!w.users.containsKey(user)) {
							User newUser = new User(user, w.prefixes);
							w.users.put(newUser.getNick(), newUser);
						}
					}
				}
				if(w.whoEnabled){
					w.send("WHO " + p.actionArgs.get(2));
				}
				break;
			case "005": // Server capabilities, sent upon connection
				for (String spec : p.actionArgs) {
					String[] kvSplitter = spec.split("=", 2);
					String key = kvSplitter[0].toUpperCase();
					String value = kvSplitter.length == 2 ? kvSplitter[1] : "";
					switch (key) {
					case "PREFIX":
						String[] splitter = value.substring(1).split("\\)");
						for (int i = 0; i < splitter[0].length(); i++) {
							w.prefixes.put(splitter[1].charAt(i),
									splitter[0].charAt(i));
						}
						break;
					case "NETWORK":
						w.serverName = value;
						w.eventHandler.onEvent(Event.GOT_SERVER_NAME, value);
						break;
					}
				}
				break;
			case "375": // Start of MOTD
				w.motd = new StringBuilder();
			case "372": // MOTD message
				w.motd.append("\n" + p.msg);
				break;
			case "376": // End of MOTD
				w.motd.trimToSize();
				w.eventHandler.onEvent(Event.GOT_MOTD, w.motd.toString()
						.substring(1));
				break;
			case "366": // Channel joined
				w.eventHandler.onEvent(Event.JOINED_CHANNEL,
						p.actionArgs.get(1));
				break;
			case "433": // Nickname in use
				if (!w.usingSecondNick) {
					w.eventHandler.onEvent(Event.FIRST_NICK_IN_USE,
							w.serverInfo.nick);
					w.register(w.serverInfo.secondNick);
				} else {
					w.eventHandler.onEvent(Event.ALL_NICKS_IN_USE,
							w.serverInfo.secondNick);
				}
				break;
			}
			break;
		}
	}
}
