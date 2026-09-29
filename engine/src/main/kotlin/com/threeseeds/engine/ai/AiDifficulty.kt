package com.threeseeds.engine.ai

/**
 * How deeply the opponent thinks. Difficulty changes DECISION QUALITY —
 * search depth and how noisy the root choice is — never artificial
 * waiting, and never the rules.
 *
 * [noise] is a random swing (in eval points, where a two-in-a-row
 * threat is worth about 70) added to each root score before the best
 * move is picked. A large noise makes the AI play noticeably worse
 * than its search would suggest, which is exactly what a beginner
 * should feel like: legal, occasionally sensible, often suboptimal.
 */
enum class AiDifficulty(
    val searchDepth: Int,
    val noise: Int,
    val timeBudgetMs: Long,
) {
    /** Legal play, almost no lookahead — blunders into simple tactics. */
    BEGINNER(searchDepth = 1, noise = 150, timeBudgetMs = 250),

    /** Sees immediate wins and blocks, little more. */
    EASY(searchDepth = 2, noise = 70, timeBudgetMs = 350),

    /** Competent tactical play; two-ply combinations. */
    MEDIUM(searchDepth = 4, noise = 20, timeBudgetMs = 600),

    /** Deeper lookahead and strong tactical defence. */
    HARD(searchDepth = 6, noise = 0, timeBudgetMs = 900),

    /** Near-full-depth search on this tiny board. */
    EXPERT(searchDepth = 8, noise = 0, timeBudgetMs = 1500),

    /** The deepest practical search; iterates until the budget runs out. */
    MASTER(searchDepth = 12, noise = 0, timeBudgetMs = 2500)
}
