package io.ipoli.android.player.usecase

import io.ipoli.android.Constants
import io.ipoli.android.achievement.usecase.UnlockAchievementsUseCase
import io.ipoli.android.achievement.usecase.UpdatePlayerStatsUseCase
import io.ipoli.android.challenge.entity.Challenge
import io.ipoli.android.common.Reward
import io.ipoli.android.common.UseCase
import io.ipoli.android.dailychallenge.data.DailyChallenge
import io.ipoli.android.habit.data.Habit
import io.ipoli.android.pet.Food
import io.ipoli.android.player.LevelUpScheduler
import io.ipoli.android.player.attribute.Booster
import io.ipoli.android.player.attribute.TotalBonusBooster
import io.ipoli.android.player.attribute.usecase.CheckForOneTimeBoostUseCase
import io.ipoli.android.player.data.Player
import io.ipoli.android.player.persistence.PlayerRepository
import io.ipoli.android.quest.Quest
import io.ipoli.android.tag.Tag
import org.threeten.bp.LocalDate
import java.util.*

/**
 * Created by Venelin Valkov <venelin@mypoli.fun>
 * on 28.11.17.
 */
open class RewardPlayerUseCase(
    private val playerRepository: PlayerRepository,
    private val levelUpScheduler: LevelUpScheduler,
    private val unlockAchievementsUseCase: UnlockAchievementsUseCase,
    private val checkForOneTimeBoostUseCase: CheckForOneTimeBoostUseCase,
    private val removeRewardFromPlayerUseCase: RemoveRewardFromPlayerUseCase,
    private val randomSeed: Long? = null
) : UseCase<RewardPlayerUseCase.Params, RewardPlayerUseCase.Result> {

    companion object {
        val QUEST_HP_BASE_REWARDS = intArrayOf(1, 2, 3)

        val QUEST_XP_BASE_REWARDS = intArrayOf(2, 5, 7, 10, 15)
        val QUEST_COINS_BASE_REWARDS = intArrayOf(1, 2, 3, 5)
        val QUEST_ATTRIBUTE_BASE_REWARDS = intArrayOf(1, 2, 3, 4)

        val HABIT_XP_BASE_REWARDS = intArrayOf(1, 2, 4, 5)
        val HABIT_COINS_BASE_REWARDS = intArrayOf(1, 2)
        val HABIT_ATTRIBUTE_BASE_REWARDS = intArrayOf(0, 1, 2)

        val DC_XP_BASE_REWARDS = QUEST_XP_BASE_REWARDS.map { it * 2 }.toIntArray()
        val DC_COINS_BASE_REWARDS = QUEST_COINS_BASE_REWARDS.map { it * 2 }.toIntArray()

        val CHALLENGE_XP_BASE_REWARDS = intArrayOf(20, 30, 40, 50, 60)
        val CHALLENGE_COINS_BASE_REWARDS = intArrayOf(10, 15, 25, 40, 50)
        val CHALLENGE_ATTRIBUTE_BASE_REWARDS = intArrayOf(3, 6, 9, 12, 15)
    }

    override fun execute(parameters: Params): Result {
        val player = parameters.player ?: playerRepository.find()!!

        val reward = when (parameters) {
            is Params.ForQuest -> {
                val quest = parameters.quest
                quest.reward ?: createRewardForQuest(quest, player, player.rank)
            }

            is Params.ForHabit -> {

                val habit = parameters.habit

                val entry = habit.history[parameters.playerDate]!!

                entry.reward ?: createRewardForHabit(habit, player, player.rank)
            }

            is Params.ForBadHabit -> {

                val habit = parameters.habit

                val entry = habit.history[parameters.playerDate]!!

                entry.reward ?: createRewardForHabit(habit, player, player.rank)
            }

            is Params.ForDailyChallenge -> {
                val dc = parameters.dailyChallenge

                dc.reward ?: createRewardForDailyChallenge(player, player.rank)
            }

            is Params.ForChallenge -> {
                val c = parameters.challenge
                c.reward ?: createRewardForChallenge(c, player, player.rank)
            }

            is Params.ForPlanDay ->
                createRewardForPlanDay()
        }

        if(parameters is Params.ForBadHabit) {
            val newPlayer = removeRewardFromPlayerUseCase.execute(RemoveRewardFromPlayerUseCase.Params(reward, player))
            return Result(newPlayer, reward)
        }

        val newPlayer = player.addReward(reward)

        if (newPlayer.level != player.level) {
            levelUpScheduler.schedule(newPlayer.level)
        }
        val p = playerRepository.save(
            checkForOneTimeBoostUseCase.execute(
                CheckForOneTimeBoostUseCase.Params(
                    newPlayer,
                    newPlayer.rank
                )
            )
        )

        unlockAchievementsUseCase.execute(
            UnlockAchievementsUseCase.Params(
                player = p,
                eventType = UpdatePlayerStatsUseCase.Params.EventType.ExperienceIncreased(newPlayer.experience - player.experience)
            )
        )
        return Result(p, reward)
    }

    private fun createRewardForPlanDay() =
        Reward(
            attributePoints = emptyMap(),
            healthPoints = 0,
            experience = experience(100, QUEST_XP_BASE_REWARDS),
            coins = coins(100, QUEST_COINS_BASE_REWARDS),
            bounty = Quest.Bounty.None
        )

    private fun createRewardForChallenge(
        challenge: Challenge,
        player: Player,
        rank: Player.Rank
    ): Reward {
        val booster = TotalBonusBooster.forChallenge(challenge, player, rank)
        val xp = experience(booster.experiencePercentage, CHALLENGE_XP_BASE_REWARDS)
        val coins = coins(booster.coinsPercentage, CHALLENGE_COINS_BASE_REWARDS)
        val bounty = bounty(booster.itemDropPercentage)

        val attrs = attributePoints(
            tags = challenge.tags,
            player = player,
            rewards = CHALLENGE_ATTRIBUTE_BASE_REWARDS,
            booster = booster
        )

        return Reward(attrs, 0, xp, coins, bounty)
    }

    private fun createRewardForDailyChallenge(player: Player, rank: Player.Rank): Reward {
        val booster = TotalBonusBooster.forDailyChallenge(player, rank)
        val xp = experience(booster.experiencePercentage, DC_XP_BASE_REWARDS)
        val coins = coins(booster.coinsPercentage, DC_COINS_BASE_REWARDS)
        val bounty = bounty(booster.itemDropPercentage)

        return Reward(emptyMap(), 0, xp, coins, bounty)
    }


    private fun createRewardForHabit(
        habit: Habit,
        player: Player,
        rank: Player.Rank
    ): Reward {
        val booster = TotalBonusBooster.forHabit(habit, player, rank)
        val xp = experience(booster.experiencePercentage, HABIT_XP_BASE_REWARDS)
        val coins = coins(booster.coinsPercentage, HABIT_COINS_BASE_REWARDS)
        val bounty = Quest.Bounty.None

        val attrs = attributePoints(
            tags = habit.tags,
            player = player,
            rewards = HABIT_ATTRIBUTE_BASE_REWARDS,
            booster = booster
        )

        return Reward(attrs, 0, xp, coins, bounty)
    }

    private fun createRewardForQuest(
        quest: Quest,
        player: Player,
        rank: Player.Rank
    ): Reward {
        val booster = TotalBonusBooster.forQuest(quest, player, rank)
        val xp = experience(booster.experiencePercentage, QUEST_XP_BASE_REWARDS)
        val coins = coins(booster.coinsPercentage, QUEST_COINS_BASE_REWARDS)
        val bounty = bounty(booster.itemDropPercentage)

        val attrs = attributePoints(
            tags = quest.tags,
            player = player,
            rewards = QUEST_ATTRIBUTE_BASE_REWARDS,
            booster = booster
        )

        return Reward(
            attrs,
            health(booster.healthPointsPercentage, QUEST_HP_BASE_REWARDS),
            xp,
            coins,
            bounty
        )
    }

    private fun attributePoints(
        tags: List<Tag>,
        player: Player,
        rewards: IntArray,
        booster: Booster
    ): Map<Player.AttributeType, Int> {
        if (tags.isEmpty()) {
            return emptyMap()
        }

        val result = mutableMapOf<Player.AttributeType, Int>()
        tags.forEach { t ->
            player.attributes.values
                .filter { it.tags.contains(t) }
                .forEach {
                    val pos = it.tags.indexOf(t)
                    val rewardPool = rewards.size - pos

                    val reward = attributePoints(it.type, rewards, rewardPool, booster)

                    if (result.containsKey(it.type)) {
                        result[it.type] = result[it.type]!! + reward
                    } else {
                        result[it.type] = reward
                    }
                }
        }

        return result.filter { it.value > 0 }
    }

    private fun attributePoints(
        attributeType: Player.AttributeType,
        rewards: IntArray,
        rewardPool: Int,
        booster: Booster
    ): Int {
        val bonusCoef = (100 + booster.attributeBonus(attributeType)).toFloat() / 100
        val reward = rewards[createRandom().nextInt(rewardPool)]
        return (reward * bonusCoef).toInt()
    }

    private fun health(healthBonusPercentage: Int, rewards: IntArray): Int {
        val bonusCoef = (100 + healthBonusPercentage.toFloat()) / 100
        val reward = rewards[createRandom().nextInt(rewards.size)]
        return (reward * bonusCoef).toInt()
    }

    private fun coins(coinBonusPercentage: Int, rewards: IntArray): Int {
        val bonusCoef = (100 + coinBonusPercentage.toFloat()) / 100
        val reward = rewards[createRandom().nextInt(rewards.size)]
        return (reward * bonusCoef).toInt()
    }

    private fun experience(xpBonusPercentage: Int, rewards: IntArray): Int {
        val bonusCoef = (100 + xpBonusPercentage.toFloat()) / 100
        val reward = rewards[createRandom().nextInt(rewards.size)]
        return (reward * bonusCoef).toInt()
    }

    private fun bounty(bountyBonusPercentage: Int): Quest.Bounty {
        val bountyBonus =
            Constants.QUEST_BOUNTY_DROP_PERCENTAGE * (bountyBonusPercentage.toFloat() / 100)
        val totalBountyPercentage = Constants.QUEST_BOUNTY_DROP_PERCENTAGE + bountyBonus

        val random = createRandom().nextDouble()
        return if (random > totalBountyPercentage / 100) {
            Quest.Bounty.None
        } else {
            chooseBounty()
        }
    }

    private fun chooseBounty(): Quest.Bounty.Food {
        val foods = Food.values() + Food.POOP + Food.POOP + Food.BEER
        val index = createRandom().nextInt(foods.size)
        return Quest.Bounty.Food(foods[index])
    }

    private fun createRandom() =
        Random().apply {
            randomSeed?.let { setSeed(it) }
        }

    sealed class Params {

        abstract val player: Player?

        data class ForQuest(val quest: Quest, override val player: Player? = null) : Params()

        data class ForHabit(
            val habit: Habit,
            val playerDate: LocalDate = LocalDate.now(),
            override val player: Player? = null
        ) : Params()

        data class ForBadHabit(
            val habit: Habit,
            val playerDate: LocalDate = LocalDate.now(),
            override val player: Player? = null
        ) : Params()

        data class ForChallenge(
            val challenge: Challenge,
            override val player: Player? = null
        ) : Params()

        data class ForDailyChallenge(
            val dailyChallenge: DailyChallenge,
            override val player: Player? = null
        ) : Params()

        data class ForPlanDay(override val player: Player? = null) : Params()
    }

    data class Result(val player: Player, val reward: Reward)
}