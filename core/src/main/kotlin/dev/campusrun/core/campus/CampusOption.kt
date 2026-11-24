package dev.campusrun.core.campus

/**
 * One campus (校区) the account is allowed to run under.
 *
 * Field-for-field the upstream entity `entity/MyCampusBean` the official client
 * deserializes from `CommonServer.getMyCampusList` (`@GET("api/v61101/campus/zonelist")`
 * with `@Query("unid")`, returning `BasicBean<List<MyCampusBean>>`) — recovered from the
 * 7.4.0 FridaDump DEX (`dump_exact_7`, class `net/CommonServer` + `entity/MyCampusBean`):
 *
 * ```text
 * allowChange : Z   isSelected : Z   name : String   unid : I
 * ```
 *
 * @param unid the campus id — the value the run policy's `selectUnid`/`selectedUnid`
 *   bodies are parameterized with (PolicyClient T6), i.e. "which campus's rule is this".
 * @param name display name of the campus (on-device only, like [dev.campusrun.core.profile.UserProfile.schoolName]).
 * @param allowChange upstream's per-campus flag (`MyCampusBean.allowChange`), parsed
 *   verbatim. It **gates** the switch affordance in code: only entries with
 *   `allowChange = true` are ever offered as choices (`HomeViewModel.campusChoices`), and
 *   the menu is rendered only while `HomeViewModel.campusSwitchable` holds — so a campus
 *   the platform marks as not changeable can never be presented as a switchable option.
 * @param isSelected the campus the platform currently marks as the account's own; it
 *   becomes the default selection (and is persisted) when the user has not chosen one,
 *   so the displayed campus and the `selectUnid` the rules are read with stay the same.
 */
data class CampusOption(
    val unid: Int,
    val name: String,
    val allowChange: Boolean,
    val isSelected: Boolean,
)
