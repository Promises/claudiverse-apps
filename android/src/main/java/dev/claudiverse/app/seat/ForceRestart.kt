package dev.claudiverse.app.seat

import dev.claudiverse.core.RestartPlan
import dev.claudiverse.core.RestartRefused

/**
 * What "Restart anyway" would override for [plan]: its skip, when that skip
 * is only an idle check (a running turn, background work, an open dialog,
 * recent activity; core's [RestartRefused.canForce]). Null for a plan that
 * can be restarted as it is, for a protected seat (its own confirm, never a
 * force), and for every other skip.
 */
fun forceableSkip(plan: RestartPlan?): RestartRefused? {
    if (plan == null || !plan.skipped || plan.choosable) return null
    return RestartRefused(plan.reason.orEmpty(), plan.reasonCode).takeIf { it.canForce }
}
