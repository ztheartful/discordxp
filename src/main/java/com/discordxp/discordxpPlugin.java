package com.discordxp;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;
import static net.runelite.api.widgets.WidgetID.QUEST_COMPLETED_GROUP_ID;
import okhttp3.*;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static net.runelite.http.api.RuneLiteAPI.GSON;

@Slf4j
@PluginDescriptor(name = "Discord Notifications")
public class discordxpPlugin extends Plugin
{
	private Hashtable<String, Integer> currentLevels;
	private Hashtable<String, Integer> oldLevels;
	private ArrayList<String> leveledSkills;
	private boolean shouldSendLevelMessage = false;
	private boolean shouldSendQuestMessage = false;
	private boolean shouldSendClueMessage = false;
	private int ticksWaited = 0;

	private static final Pattern QUEST_PATTERN_1 = Pattern.compile(".+?ve\\.*? (?<verb>been|rebuilt|.+?ed)? ?(?:the )?'?(?<quest>.+?)'?(?: [Qq]uest)?[!.]?$");
	private static final Pattern QUEST_PATTERN_2 = Pattern.compile("'?(?<quest>.+?)'?(?: [Qq]uest)? (?<verb>[a-z]\\w+?ed)?(?: f.*?)?[!.]?$");
	private static final ImmutableList<String> RFD_TAGS = ImmutableList.of("Another Cook", "freed", "defeated", "saved");
	private static final ImmutableList<String> WORD_QUEST_IN_NAME_TAGS = ImmutableList.of("Another Cook", "Doric", "Heroes", "Legends", "Observatory", "Olaf", "Waterfall");
	private static final ImmutableList<String> PET_MESSAGES = ImmutableList.of("You have a funny feeling like you're being followed",
			"You feel something weird sneaking into your backpack",
			"You have a funny feeling like you would have been followed");

	@Inject
	private Client client;

	@Inject
	private discordxpConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private DrawManager drawManager;

	@Provides
    discordxpConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(discordxpConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		currentLevels = new Hashtable<String, Integer>();
		oldLevels = new Hashtable<String, Integer>();
		leveledSkills = new ArrayList<String>();
	}

	@Override
	protected void shutDown() throws Exception
	{

	}

	@Subscribe
	public void onUsernameChanged(UsernameChanged usernameChanged)
	{
		resetState();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState().equals(GameState.LOGIN_SCREEN))
		{
			resetState();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{

		boolean didCompleteClue = client.getWidget(WidgetInfo.CLUE_SCROLL_REWARD_ITEM_CONTAINER) != null;

		if (shouldSendClueMessage && didCompleteClue && config.sendClue()) {
			shouldSendClueMessage = false;
			sendClueMessage();
		}

		if (
				shouldSendQuestMessage
						&& config.sendQuestComplete()
						&& client.getWidget(WidgetInfo.QUEST_COMPLETED_NAME_TEXT) != null
		) {
			shouldSendQuestMessage = false;
			String text = client.getWidget(WidgetInfo.QUEST_COMPLETED_NAME_TEXT).getText();
			String questName = parseQuestCompletedWidget(text);
			sendQuestMessage(questName);
		}

		if (!shouldSendLevelMessage)
		{
			return;
		}

		if (ticksWaited < 2)
		{
			ticksWaited++;
			return;
		}

		shouldSendLevelMessage = false;
		ticksWaited = 0;
		sendLevelMessage();
	}
	public Integer newxp;
	public Integer oldxp;
	public String previousskill;
	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		if (!config.sendLevelling())
		{
			return;
		}

		String skillName = statChanged.getSkill().getName();


		oldxp = oldLevels.get(skillName);
		newxp = statChanged.getXp();
		if (oldxp == null || oldxp == 0 ){

			oldxp=0;

		}



		Integer newLevel = newxp - oldxp;
		oldLevels.put(skillName,newxp);



		// .contains wasn't behaving so I went with == null
		Integer previousLevel = currentLevels.get(skillName);
		if (previousLevel == null || previousLevel == 0)
		{
			currentLevels.put(skillName, newLevel);
			return;
		}


		{
			currentLevels.put(skillName, newLevel);


					leveledSkills.add(skillName);
					shouldSendLevelMessage = true;
			previousskill = skillName;



		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String chatMessage = event.getMessage();
		if (config.setPets() && PET_MESSAGES.stream().anyMatch(chatMessage::contains))
		{
			sendPetMessage();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath actorDeath)
	{
		if (config.sendDeath() == false) {
			return;
		}

		Actor actor = actorDeath.getActor();
		if (actor instanceof Player)
		{
			Player player = (Player) actor;
			if (player == client.getLocalPlayer())
			{
				sendDeathMessage();
			}
		}
	}

	private boolean shouldSendForThisLevel(int level)
	{
		return level >= config.minLevel()
				&& levelMeetsIntervalRequirement(level);
	}

	private boolean levelMeetsIntervalRequirement(int level) {
		int levelInterval = config.levelInterval();

		if (config.linearLevelMax() > 0) {
			levelInterval = (int) Math.max(Math.ceil(-.1*level + config.linearLevelMax()), 1);
		}

		return levelInterval <= 1
				|| level == 99
				|| level % levelInterval == 0;
	}

	private void sendQuestMessage(String questName)
	{
		String localName = client.getLocalPlayer().getName();

		String questMessageString = config.questMessage().replaceAll("\\$name", localName)
														 .replaceAll("\\$quest", questName);

		discordWebhookBody discordWebhookBody = new discordWebhookBody();
		discordWebhookBody.setContent(questMessageString);
		sendWebhook(discordWebhookBody, config.sendQuestingScreenshot());
	}

	private void sendDeathMessage()
	{
		String localName = client.getLocalPlayer().getName();

		String deathMessageString = config.deathMessage().replaceAll("\\$name", localName);

		discordWebhookBody discordWebhookBody = new discordWebhookBody();
		discordWebhookBody.setContent(deathMessageString);
		sendWebhook(discordWebhookBody, config.sendDeathScreenshot());
	}

	private void sendClueMessage()
	{
		String localName = client.getLocalPlayer().getName();

		String clueMessage = config.clueMessage().replaceAll("\\$name", localName);

		discordWebhookBody discordWebhookBody = new discordWebhookBody();
		discordWebhookBody.setContent(clueMessage);
		sendWebhook(discordWebhookBody, config.sendClueScreenshot());
	}

	private void sendLevelMessage()
	{
		String localName = client.getLocalPlayer().getName();

		String levelUpString = config.levelMessage().replaceAll("\\$name", localName);

		String[] skills = new String[leveledSkills.size()];
		skills = leveledSkills.toArray(skills);
		leveledSkills.clear();

		for (int i = 0; i < skills.length; i++)
		{
			if(i != 0) {
				levelUpString += config.andLevelMessage();
			}
			
			if(config.sendTotalLevel()) {
				levelUpString += config.totalLevelMessage();
			}

			String fixed = levelUpString
					.replaceAll("\\$skill", skills[i])
					.replaceAll("\\$level", currentLevels.get(skills[i]).toString())
					.replaceAll("\\$total" , Integer.toString(client.getTotalLevel()));

			levelUpString = fixed;
		}

		discordWebhookBody discordWebhookBody = new discordWebhookBody();
		discordWebhookBody.setContent(levelUpString);
		sendWebhook(discordWebhookBody, config.sendLevellingScreenshot());
	}

	private void sendPetMessage()
	{
		String localName = client.getLocalPlayer().getName();

		String petMessageString = config.petMessage().replaceAll("\\$name", localName);

		discordWebhookBody discordWebhookBody = new discordWebhookBody();
		discordWebhookBody.setContent(petMessageString);
		sendWebhook(discordWebhookBody, config.sendPetScreenshot());
	}

	private void sendWebhook(discordWebhookBody discordWebhookBody, boolean sendScreenshot)
	{
		String configUrl = config.webhook();
		if (Strings.isNullOrEmpty(configUrl)) { return; }

		List<String> webhookUrls = Arrays.asList(configUrl.split("\n"))
				.stream()
				.filter(u -> u.length() > 0)
				.map(u -> u.trim())
				.collect(Collectors.toList());

		for (String webhookUrl : webhookUrls)
		{
			HttpUrl url = HttpUrl.parse(webhookUrl);
			MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
					.setType(MultipartBody.FORM)
					.addFormDataPart("payload_json", GSON.toJson(discordWebhookBody));

			if (sendScreenshot)
			{
				sendWebhookWithScreenshot(url, requestBodyBuilder);
			}
			else
			{
				buildRequestAndSend(url, requestBodyBuilder);
			}
		}
	}

	private void sendWebhookWithScreenshot(HttpUrl url, MultipartBody.Builder requestBodyBuilder)
	{
		drawManager.requestNextFrameListener(image ->
		{
			BufferedImage bufferedImage = (BufferedImage) image;
			byte[] imageBytes;
			try
			{
				imageBytes = convertImageToByteArray(bufferedImage);
			}
			catch (IOException e)
			{
				log.warn("Error converting image to byte array", e);
				return;
			}

			requestBodyBuilder.addFormDataPart("file", "image.png",
					RequestBody.create(MediaType.parse("image/png"), imageBytes));
			buildRequestAndSend(url, requestBodyBuilder);
		});
	}

	private void buildRequestAndSend(HttpUrl url, MultipartBody.Builder requestBodyBuilder)
	{
		RequestBody requestBody = requestBodyBuilder.build();
		Request request = new Request.Builder()
				.url(url)
				.post(requestBody)
				.build();
		sendRequest(request);
	}

	private void sendRequest(Request request)
	{
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Error submitting webhook", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				response.close();
			}
		});
	}

	private static byte[] convertImageToByteArray(BufferedImage bufferedImage) throws IOException
	{
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
		return byteArrayOutputStream.toByteArray();
	}

	private void resetState()
	{
		currentLevels.clear();
		leveledSkills.clear();
		shouldSendLevelMessage = false;
		shouldSendQuestMessage = false;
		shouldSendClueMessage = false;
		ticksWaited = 0;
	}

	static String parseQuestCompletedWidget(final String text)
	{
		// "You have completed The Corsair Curse!"
		final Matcher questMatch1 = QUEST_PATTERN_1.matcher(text);
		// "'One Small Favour' completed!"
		final Matcher questMatch2 = QUEST_PATTERN_2.matcher(text);
		final Matcher questMatchFinal = questMatch1.matches() ? questMatch1 : questMatch2;
		if (!questMatchFinal.matches())
		{
			return "Unable to find quest name!";
		}

		String quest = questMatchFinal.group("quest");
		String verb = questMatchFinal.group("verb") != null ? questMatchFinal.group("verb") : "";

		if (verb.contains("kind of"))
		{
			quest += " partial completion";
		}
		else if (verb.contains("completely"))
		{
			quest += " II";
		}

		if (RFD_TAGS.stream().anyMatch((quest + verb)::contains))
		{
			quest = "Recipe for Disaster - " + quest;
		}

		if (WORD_QUEST_IN_NAME_TAGS.stream().anyMatch(quest::contains))
		{
			quest += " Quest";
		}

		return quest;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		int groupId = event.getGroupId();

		if (groupId == QUEST_COMPLETED_GROUP_ID) {
			shouldSendQuestMessage = true;
		}

		if (groupId == WidgetID.CLUE_SCROLL_REWARD_GROUP_ID) {
			shouldSendClueMessage = true;
		}
	}
}
