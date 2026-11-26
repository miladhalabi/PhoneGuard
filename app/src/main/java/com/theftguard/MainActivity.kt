package com.theftguard

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private class Step(
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        @StringRes val subtitle: Int,
        @StringRes val action: Int,
        /** False for steps Android can't report on; they never show as done. */
        val trackable: Boolean,
        val isDone: () -> Boolean,
        val run: () -> Unit,
    ) {
        lateinit var row: View
        var shownDone: Boolean? = null
    }

    private lateinit var root: View
    private lateinit var steps: List<Step>

    private lateinit var heroCard: MaterialCardView
    private lateinit var heroIconBg: FrameLayout
    private lateinit var heroIcon: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var heroProgress: LinearProgressIndicator
    private var heroDone: Boolean? = null

    private lateinit var pinSetRow: View
    private lateinit var pinForm: View
    private lateinit var tilPin: TextInputLayout
    private lateinit var tilPinConfirm: TextInputLayout
    private lateinit var btnPinCancel: MaterialButton
    private var editingPin = false

    private lateinit var sendersToggle: MaterialButtonToggleGroup
    private lateinit var sendersHelp: TextView
    private lateinit var chips: ChipGroup
    private lateinit var trustedEmpty: TextView
    private lateinit var tilAdd: TextInputLayout

    private lateinit var lostBanner: View
    private lateinit var lostBannerText: TextView

    private lateinit var btnTest: MaterialButton
    private lateinit var testProgress: LinearProgressIndicator
    private lateinit var testResult: View
    private lateinit var testText: TextView
    private lateinit var btnMaps: MaterialButton
    private var testLocator: Locator? = null
    private var testLocation: Location? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { !it }) showDenied()
            refresh()
        }

    private val contactLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.data?.let(::addFromContact)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)

        applyInsets()
        setupHero()
        setupSteps()
        setupPin()
        setupSenders()
        setupAlerts()
        setupLost()
        setupLockReply()
        setupTest()

        listOf(R.id.content, R.id.pinCard, R.id.sendersCard, R.id.lostCard, R.id.testCard)
            .forEach { smoothLayoutChanges(findViewById(it)) }
    }

    override fun onResume() {
        super.onResume()
        SimWatcher.remember(this)
        refresh()
    }

    override fun onDestroy() {
        testLocator = null
        super.onDestroy()
    }

    // region Layout plumbing

    private fun applyInsets() {
        val scroll = findViewById<NestedScrollView>(R.id.scroll)
        val base = scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom
            v.updatePadding(bottom = base + bottom)
            insets
        }
    }

    /** Sections that appear or disappear slide neighbours into place instead of jumping. */
    private fun smoothLayoutChanges(group: ViewGroup) {
        group.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(LAYOUT_MS)
            setInterpolator(LayoutTransition.CHANGING, EASE_OUT)
            setInterpolator(LayoutTransition.CHANGE_APPEARING, EASE_OUT)
            setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, EASE_OUT)
            // Fade in only after the space has opened, so content doesn't overlap its neighbours.
            setStartDelay(LayoutTransition.APPEARING, LAYOUT_MS / 2)
            setStartDelay(LayoutTransition.DISAPPEARING, 0)
            setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0)
        }
    }

    private fun refresh() {
        renderLostBanner()
        steps.forEach(::renderStep)
        renderPin()
        renderSenders()
        renderHero()
    }

    // endregion

    // region Status card

    private fun setupHero() {
        heroCard = findViewById(R.id.heroCard)
        heroIconBg = findViewById(R.id.heroIconBg)
        heroIcon = findViewById(R.id.heroIcon)
        heroTitle = findViewById(R.id.heroTitle)
        heroSubtitle = findViewById(R.id.heroSubtitle)
        heroProgress = findViewById(R.id.heroProgress)
    }

    private fun renderHero() {
        val trustedMode = !Prefs.anyNumber(this)
        // Restricting commands, or any alert, only works with at least one number to talk to.
        val needsNumber = trustedMode || Prefs.simAlert(this) || Prefs.unlockAlert(this)
        val missing = buildList {
            steps.filter { it.trackable && !it.isDone() }.forEach { add(getString(it.title)) }
            if (!Prefs.hasPin(this@MainActivity)) add(getString(R.string.step_pin))
            if (needsNumber && Prefs.trustedList(this@MainActivity).isEmpty()) add(getString(R.string.step_trusted))
        }
        val total = steps.count { it.trackable } + 1 + (if (needsNumber) 1 else 0)
        val done = missing.isEmpty()

        if (done) {
            heroTitle.setText(R.string.hero_done_title)
            heroSubtitle.setText(if (trustedMode) R.string.hero_done_trusted else R.string.hero_done_any)
        } else {
            heroTitle.setText(R.string.hero_todo_title)
            heroSubtitle.text = resources.getQuantityString(R.plurals.hero_steps_left, missing.size, missing.size, missing.first())
        }
        heroProgress.setProgressCompat((total - missing.size) * 100 / total, heroDone != null)
        heroProgress.isVisible = !done

        if (heroDone == done) return
        val animate = heroDone != null
        heroDone = done
        heroIcon.setImageResource(if (done) R.drawable.ic_verified else R.drawable.ic_shield_outline)

        val container = if (done) ContextCompat.getColor(this, R.color.successContainer)
        else MaterialColors.getColor(heroCard, com.google.android.material.R.attr.colorPrimaryContainer)
        val onContainer = if (done) ContextCompat.getColor(this, R.color.onSuccessContainer)
        else MaterialColors.getColor(heroCard, com.google.android.material.R.attr.colorOnPrimaryContainer)
        val accent = if (done) ContextCompat.getColor(this, R.color.success)
        else MaterialColors.getColor(heroCard, androidx.appcompat.R.attr.colorPrimary)
        val onAccent = if (done) ContextCompat.getColor(this, R.color.onSuccess)
        else MaterialColors.getColor(heroCard, com.google.android.material.R.attr.colorOnPrimary)

        heroTitle.setTextColor(onContainer)
        heroSubtitle.setTextColor(onContainer)
        heroIcon.imageTintList = ColorStateList.valueOf(onAccent)
        if (animate) {
            val from = heroCard.cardBackgroundColor.defaultColor
            val fromAccent = heroIconBg.backgroundTintList?.defaultColor ?: accent
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = COLOR_MS
                interpolator = EASE_OUT
                val argb = ArgbEvaluator()
                addUpdateListener {
                    val f = it.animatedFraction
                    heroCard.setCardBackgroundColor(argb.evaluate(f, from, container) as Int)
                    heroIconBg.backgroundTintList = ColorStateList.valueOf(argb.evaluate(f, fromAccent, accent) as Int)
                }
                start()
            }
            popIn(heroIcon)
        } else {
            heroCard.setCardBackgroundColor(container)
            heroIconBg.backgroundTintList = ColorStateList.valueOf(accent)
        }
    }

    // endregion

    // region Setup steps

    private fun setupSteps() {
        val isXiaomi = Build.MANUFACTURER.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco")
        steps = listOfNotNull(
            Step(R.drawable.ic_sms, R.string.step_sms, R.string.step_sms_desc, R.string.action_allow, true, ::hasSms, ::requestBase),
            Step(R.drawable.ic_location, R.string.step_location, R.string.step_location_desc, R.string.action_allow, true, ::hasLocation, ::requestBase),
            Step(R.drawable.ic_my_location, R.string.step_background, R.string.step_background_desc, R.string.action_allow, true, ::hasBackground, ::requestBackground),
            Step(R.drawable.ic_lock, R.string.step_admin, R.string.step_admin_desc, R.string.action_enable, true, { Device.canWatchUnlocks(this) }, ::requestAdmin),
            Step(R.drawable.ic_battery, R.string.step_battery, R.string.step_battery_desc, R.string.action_allow, true, ::isBatteryExempt, ::requestBattery),
            Step(R.drawable.ic_dialpad, R.string.step_screen_lock, R.string.step_screen_lock_desc, R.string.action_set_up, true, ::isScreenLockSet) {
                open(Intent(Settings.ACTION_SECURITY_SETTINGS))
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Step(R.drawable.ic_shield_outline, R.string.step_fullscreen, R.string.step_fullscreen_desc, R.string.action_allow, true, { !Prefs.lostScreen(this) || canFullScreen() }) {
                    open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                }
            } else {
                null
            },
            if (isXiaomi) {
                Step(R.drawable.ic_power, R.string.step_autostart, R.string.step_autostart_desc, R.string.action_open, false, { false }, ::openAutostart)
            } else {
                null
            },
        )

        val list = findViewById<LinearLayout>(R.id.stepList)
        steps.forEachIndexed { i, step ->
            if (i > 0) {
                list.addView(MaterialDivider(this).apply {
                    dividerInsetStart = dp(72)
                    dividerInsetEnd = dp(16)
                })
            }
            val row = layoutInflater.inflate(R.layout.item_step, list, false)
            row.findViewById<ImageView>(R.id.icon).setImageResource(step.icon)
            row.findViewById<TextView>(R.id.title).setText(step.title)
            row.findViewById<TextView>(R.id.subtitle).setText(step.subtitle)
            row.findViewById<MaterialButton>(R.id.action).apply {
                setText(step.action)
                setOnClickListener { step.run() }
            }
            row.setOnClickListener { step.run() }
            step.row = row
            list.addView(row)
        }
    }

    private fun renderStep(step: Step) {
        val done = step.trackable && step.isDone()
        if (step.shownDone == done) return
        val changed = step.shownDone != null
        step.shownDone = done

        val action = step.row.findViewById<View>(R.id.action)
        val check = step.row.findViewById<View>(R.id.done)
        action.isVisible = !done
        check.isVisible = done
        step.row.isClickable = !done
        if (changed && done) popIn(check)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun hasSms() = granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.SEND_SMS) &&
        granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.CALL_PHONE)
    private fun canFullScreen() = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    private fun hasLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasBackground() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    private fun isBatteryExempt() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    private fun isScreenLockSet() = getSystemService(KeyguardManager::class.java).isDeviceSecure

    private fun requestBase() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.filterNot(::granted).ifEmpty { perms }.toTypedArray())
    }

    @SuppressLint("InlinedApi")
    private fun requestBackground() {
        if (!hasLocation()) {
            snack(getString(R.string.msg_need_location))
            requestBase()
            return
        }
        // On Android 11+ this opens the app's location page, where "Allow all the time" lives.
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    private fun requestAdmin() {
        open(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Device.adminComponent(this))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_description))
        )
    }

    @SuppressLint("BatteryLife")
    private fun requestBattery() {
        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun openAutostart() {
        val miui = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        try {
            startActivity(miui)
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openAppSettings()
        }
    }

    private fun showDenied() {
        Snackbar.make(root, R.string.msg_denied, Snackbar.LENGTH_LONG)
            .setAction(R.string.open_settings) { openAppSettings() }
            .show()
    }

    // endregion

    // region PIN

    private fun setupPin() {
        pinSetRow = findViewById(R.id.pinSetRow)
        pinForm = findViewById(R.id.pinForm)
        tilPin = findViewById(R.id.tilPin)
        tilPinConfirm = findViewById(R.id.tilPinConfirm)
        btnPinCancel = findViewById(R.id.btnPinCancel)

        tilPin.editText!!.doAfterTextChanged { tilPin.error = null }
        tilPinConfirm.editText!!.doAfterTextChanged { tilPinConfirm.error = null }
        tilPinConfirm.editText!!.setOnEditorActionListener { _, id, _ ->
            (id == EditorInfo.IME_ACTION_DONE).also { if (it) savePin() }
        }

        findViewById<MaterialButton>(R.id.btnPinChange).setOnClickListener {
            editingPin = true
            renderPin()
            tilPin.editText!!.requestFocus()
            showKeyboard(tilPin.editText!!)
        }
        btnPinCancel.setOnClickListener {
            editingPin = false
            clearPinFields()
            hideKeyboard()
            renderPin()
        }
        findViewById<MaterialButton>(R.id.btnPinSave).setOnClickListener { savePin() }
    }

    private fun renderPin() {
        val hasPin = Prefs.hasPin(this)
        pinSetRow.isVisible = hasPin && !editingPin
        pinForm.isVisible = !hasPin || editingPin
        btnPinCancel.isVisible = hasPin
    }

    private fun savePin() {
        val pin = Text.digitsOnly(tilPin.editText!!.text.toString())
        val confirm = Text.digitsOnly(tilPinConfirm.editText!!.text.toString())
        when {
            pin.length < 4 -> {
                tilPin.error = getString(R.string.err_pin_short)
                reject(tilPin)
                return
            }
            pin != confirm -> {
                tilPinConfirm.error = getString(R.string.err_pin_mismatch)
                reject(tilPinConfirm)
                return
            }
        }
        Prefs.setPin(this, pin)
        editingPin = false
        clearPinFields()
        hideKeyboard()
        root.performHapticFeedback(CONFIRM)
        snack(getString(R.string.msg_pin_saved))
        refresh()
    }

    private fun clearPinFields() {
        tilPin.editText!!.text = null
        tilPinConfirm.editText!!.text = null
        tilPin.error = null
        tilPinConfirm.error = null
    }

    // endregion

    // region Who can send commands

    private fun setupSenders() {
        sendersToggle = findViewById(R.id.sendersToggle)
        sendersHelp = findViewById(R.id.sendersHelp)
        chips = findViewById(R.id.chips)
        trustedEmpty = findViewById(R.id.trustedEmpty)
        tilAdd = findViewById(R.id.tilAdd)

        sendersToggle.check(if (Prefs.anyNumber(this)) R.id.btnAny else R.id.btnTrusted)
        sendersToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            Prefs.setAnyNumber(this, id == R.id.btnAny)
            renderSenders()
            renderHero()
        }

        Prefs.trustedList(this).forEach(::addChip)

        val input = tilAdd.editText!!
        input.doAfterTextChanged { tilAdd.error = null }
        input.setOnEditorActionListener { _, id, _ ->
            (id == EditorInfo.IME_ACTION_DONE).also { if (it) addNumber(input.text.toString()) }
        }
        tilAdd.setEndIconOnClickListener { addNumber(input.text.toString()) }

        findViewById<MaterialButton>(R.id.btnPickContact).setOnClickListener {
            try {
                contactLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            } catch (e: ActivityNotFoundException) {
                tilAdd.editText!!.requestFocus()
            }
        }
    }

    private fun renderSenders() {
        val any = Prefs.anyNumber(this)
        sendersHelp.setText(if (any) R.string.senders_any_help else R.string.senders_trusted_help)
        val empty = chips.childCount == 0
        chips.isVisible = !empty
        trustedEmpty.isVisible = empty
    }

    private fun addNumber(raw: String) {
        if (raw.isBlank()) return
        when (Prefs.addTrusted(this, raw)) {
            Prefs.AddResult.ADDED -> {
                addChip(Prefs.trustedList(this).last())
                tilAdd.editText!!.text = null
                root.performHapticFeedback(CONFIRM)
                renderSenders()
                renderHero()
            }
            Prefs.AddResult.INVALID -> {
                tilAdd.error = getString(R.string.err_number_invalid)
                reject(tilAdd)
            }
            Prefs.AddResult.DUPLICATE -> {
                tilAdd.error = getString(R.string.err_number_duplicate)
                reject(tilAdd)
            }
        }
    }

    private fun addFromContact(uri: Uri) {
        try {
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
                if (it.moveToFirst()) addNumber(it.getString(0) ?: "")
            }
        } catch (e: SecurityException) {
            tilAdd.editText!!.requestFocus()
        }
    }

    private fun addChip(number: String) {
        val chip = layoutInflater.inflate(R.layout.item_chip, chips, false) as Chip
        chip.text = number
        chip.setOnCloseIconClickListener {
            val index = chips.indexOfChild(chip)
            Prefs.removeTrusted(this, number)
            chips.removeView(chip)
            renderSenders()
            renderHero()
            Snackbar.make(root, getString(R.string.msg_number_removed, number), Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) {
                    if (Prefs.addTrusted(this, number) == Prefs.AddResult.ADDED) {
                        chips.addView(chip, index.coerceAtMost(chips.childCount))
                        renderSenders()
                        renderHero()
                    }
                }
                .show()
        }
        chips.addView(chip)
    }

    // endregion

    // region Alerts

    private fun setupAlerts() {
        val list = findViewById<LinearLayout>(R.id.alertList)
        addToggle(list, R.drawable.ic_sim, R.string.alert_sim, R.string.alert_sim_desc, Prefs.simAlert(this)) {
            Prefs.setSimAlert(this, it)
        }
        list.addView(MaterialDivider(this).apply {
            dividerInsetStart = dp(72)
            dividerInsetEnd = dp(16)
        })
        addToggle(list, R.drawable.ic_password_fail, R.string.alert_unlock, R.string.alert_unlock_desc, Prefs.unlockAlert(this)) {
            Prefs.setUnlockAlert(this, it)
        }
        list.addView(MaterialDivider(this).apply {
            dividerInsetStart = dp(72)
            dividerInsetEnd = dp(16)
        })
        addToggle(list, R.drawable.ic_battery, R.string.alert_battery, R.string.alert_battery_desc, Prefs.lowBatteryAlert(this)) {
            Prefs.setLowBatteryAlert(this, it)
        }
    }

    private fun addToggle(
        list: LinearLayout,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes subtitle: Int,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val row = layoutInflater.inflate(R.layout.item_toggle, list, false)
        row.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.title).setText(title)
        row.findViewById<TextView>(R.id.subtitle).setText(subtitle)
        val toggle = row.findViewById<MaterialSwitch>(R.id.toggle)
        toggle.isChecked = initial
        toggle.setOnCheckedChangeListener { _, on ->
            onChange(on)
            renderHero()
        }
        row.setOnClickListener { toggle.toggle() }
        list.addView(row)
    }

    // endregion

    // region Lost mode

    private fun setupLost() {
        lostBanner = findViewById(R.id.lostBanner)
        lostBannerText = findViewById(R.id.lostBannerText)
        findViewById<MaterialButton>(R.id.btnLostOff).setOnClickListener {
            LostMode.exit(this)
            AlarmService.stop(this)
            root.performHapticFeedback(CONFIRM)
            snack(getString(R.string.msg_lost_off))
            refresh()
        }

        val fields = findViewById<View>(R.id.lostFields)
        val toggle = findViewById<MaterialSwitch>(R.id.lostToggle)
        toggle.isChecked = Prefs.lostScreen(this)
        fields.isVisible = toggle.isChecked
        toggle.setOnCheckedChangeListener { _, on ->
            Prefs.setLostScreen(this, on)
            if (!on) hideKeyboard()
            fields.isVisible = on
            // The "Lost screen" setup step only matters while this is on.
            refresh()
        }
        findViewById<View>(R.id.lostToggleRow).setOnClickListener { toggle.toggle() }

        val message = findViewById<TextInputLayout>(R.id.tilLostMessage).editText!!
        message.setText(Prefs.lostMessage(this))
        message.doAfterTextChanged { Prefs.setLostMessage(this, it?.toString().orEmpty().ifBlank { Prefs.DEFAULT_LOST_MESSAGE }) }

        val contact = findViewById<TextInputLayout>(R.id.tilLostContact).editText!!
        contact.setText(Prefs.lostContactRaw(this))
        contact.doAfterTextChanged { Prefs.setLostContact(this, Text.normalizeDigits(it?.toString().orEmpty())) }
        contact.setOnEditorActionListener { _, id, _ ->
            (id == EditorInfo.IME_ACTION_DONE).also { if (it) hideKeyboard() }
        }

        findViewById<MaterialButton>(R.id.btnLostPreview).setOnClickListener {
            hideKeyboard()
            startActivity(Intent(this, LostActivity::class.java))
        }
    }

    private fun renderLostBanner() {
        val since = Prefs.lostSince(this)
        lostBanner.isVisible = since > 0
        if (since == 0L) return
        val time = DateFormat.getTimeFormat(this).format(Date(since))
        lostBannerText.text = if (Tracker.isOn(this)) {
            getString(R.string.lost_banner_tracking, time, Prefs.trackLeft(this))
        } else {
            getString(R.string.lost_banner_since, time)
        }
    }

    // endregion

    // region LOCK reply

    private fun setupLockReply() {
        val ids = mapOf(
            Prefs.LockReply.NONE to R.id.btnReplyNone,
            Prefs.LockReply.LOCKED to R.id.btnReplyLocked,
            Prefs.LockReply.LOCATION to R.id.btnReplyLocation,
        )
        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.lockReplyToggle)
        toggle.check(ids.getValue(Prefs.lockReply(this)))
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (checked) ids.entries.first { it.value == id }.let { Prefs.setLockReply(this, it.key) }
        }
    }

    // endregion

    // region Test

    private fun setupTest() {
        btnTest = findViewById(R.id.btnTest)
        testProgress = findViewById(R.id.testProgress)
        testResult = findViewById(R.id.testResult)
        testText = findViewById(R.id.testText)
        btnMaps = findViewById(R.id.btnMaps)

        btnTest.setOnClickListener { runTest() }
        btnMaps.setOnClickListener {
            val loc = testLocation ?: return@setOnClickListener
            val url = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", loc.latitude, loc.longitude)
            open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun runTest() {
        if (!hasLocation()) {
            snack(getString(R.string.msg_need_location))
            requestBase()
            return
        }
        if (testLocator != null) return
        btnTest.isEnabled = false
        btnTest.setText(R.string.test_locating)
        testProgress.show()
        testLocator = Locator(this) { result ->
            testLocator = null
            if (isDestroyed) return@Locator
            testLocation = result.location
            testProgress.hide()
            btnTest.isEnabled = true
            btnTest.setText(R.string.test_again)
            testText.text = Locator.describe(this, result)
            btnMaps.isVisible = result.location != null
            testResult.isVisible = true
        }.also { it.start(TEST_TIMEOUT_MS) }
    }

    // endregion

    // region Small helpers

    /** Something just became true: grow in from slightly small, never from nothing. */
    private fun popIn(v: View) {
        v.alpha = 0f
        v.scaleX = 0.8f
        v.scaleY = 0.8f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(POP_MS).setInterpolator(EASE_OUT).start()
    }

    /** Short horizontal shake plus haptic, so an error is felt as well as read. */
    private fun reject(v: View) {
        v.performHapticFeedback(REJECT)
        v.animate().cancel()
        v.translationX = 0f
        val d = dp(6).toFloat()
        ValueAnimator.ofFloat(0f, -d, d, -d / 2, d / 2, 0f).apply {
            duration = SHAKE_MS
            addUpdateListener { v.translationX = it.animatedValue as Float }
            start()
        }
    }

    private fun snack(msg: String) {
        Snackbar.make(root, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun showKeyboard(v: View) {
        getSystemService(InputMethodManager::class.java).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // endregion

    companion object {
        private const val TEST_TIMEOUT_MS = 30_000L
        private const val LAYOUT_MS = 220L
        private const val COLOR_MS = 260L
        private const val POP_MS = 220L
        private const val SHAKE_MS = 320L

        private val EASE_OUT = PathInterpolator(0.23f, 1f, 0.32f, 1f)

        private val CONFIRM =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        private val REJECT =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
            else HapticFeedbackConstants.LONG_PRESS
    }
}
