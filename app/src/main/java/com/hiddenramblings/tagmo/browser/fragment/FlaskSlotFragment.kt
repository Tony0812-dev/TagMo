package com.hiddenramblings.tagmo.browser.fragment

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.SearchManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.LeScanCallback
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.content.*
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.*
import androidx.cardview.widget.CardView
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.google.android.material.snackbar.Snackbar
import com.hiddenramblings.tagmo.*
import com.hiddenramblings.tagmo.amiibo.Amiibo
import com.hiddenramblings.tagmo.amiibo.AmiiboFile
import com.hiddenramblings.tagmo.amiibo.AmiiboManager
import com.hiddenramblings.tagmo.amiibo.AmiiboManager.getAmiiboManager
import com.hiddenramblings.tagmo.amiibo.AmiiboManager.hasSpoofData
import com.hiddenramblings.tagmo.amiibo.FlaskTag
import com.hiddenramblings.tagmo.amiibo.KeyManager
import com.hiddenramblings.tagmo.amiibo.tagdata.AmiiboData
import com.hiddenramblings.tagmo.bluetooth.BluetoothHandler
import com.hiddenramblings.tagmo.bluetooth.BluetoothHandler.BluetoothListener
import com.hiddenramblings.tagmo.bluetooth.FlaskGattService
import com.hiddenramblings.tagmo.bluetooth.PuckGattService
import com.hiddenramblings.tagmo.browser.BrowserActivity
import com.hiddenramblings.tagmo.browser.BrowserSettings
import com.hiddenramblings.tagmo.browser.ImageActivity
import com.hiddenramblings.tagmo.browser.adapter.FlaskSlotAdapter
import com.hiddenramblings.tagmo.browser.adapter.WriteTagAdapter
import com.hiddenramblings.tagmo.eightbit.io.Debug
import com.hiddenramblings.tagmo.eightbit.material.IconifiedSnackbar
import com.hiddenramblings.tagmo.eightbit.os.Version
import com.hiddenramblings.tagmo.nfctech.TagArray
import com.hiddenramblings.tagmo.widget.Toasty
import com.shawnlin.numberpicker.NumberPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.text.ParseException

@SuppressLint("NewApi")
open class FlaskSlotFragment : Fragment(), FlaskSlotAdapter.OnAmiiboClickListener, BluetoothListener {
    private val prefs: Preferences by lazy { Preferences(TagMo.appContext) }
    private val keyManager: KeyManager by lazy { KeyManager(TagMo.appContext) }

    private var bluetoothHandler: BluetoothHandler? = null
    private var isFragmentVisible = false
    private var amiiboTile: CardView? = null
    private var amiiboCard: CardView? = null
    private var toolbar: Toolbar? = null
    private lateinit var amiiboTileTarget: CustomTarget<Bitmap?>
    private lateinit var amiiboCardTarget: CustomTarget<Bitmap?>
    var flaskContent: RecyclerView? = null
        private set
    var flaskAdapter: FlaskSlotAdapter? = null
    private var flaskStats: TextView? = null
    private lateinit var flaskSlotCount: NumberPicker
    private var screenOptions: LinearLayout? = null
    private var writeSlots: AppCompatButton? = null
    private var writeSerials: SwitchCompat? = null
    private var eraseSlots: AppCompatButton? = null
    private var slotOptionsMenu: LinearLayout? = null
    private var createBlank: AppCompatButton? = null
    private var switchMenuOptions: AppCompatToggleButton? = null
    private var writeSlotsLayout: LinearLayout? = null
    private var writeTagAdapter: WriteTagAdapter? = null
    private var statusBar: Snackbar? = null
    private var processDialog: Dialog? = null
    private lateinit var settings: BrowserSettings
    var bottomSheet: BottomSheetBehavior<View>? = null
        private set
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var scanCallbackFlaskLP: ScanCallback? = null
    private var scanCallbackFlask: LeScanCallback? = null
    private var serviceFlask: FlaskGattService? = null
    private var scanCallbackPuckLP: ScanCallback? = null
    private var scanCallbackPuck: LeScanCallback? = null
    private var servicePuck: PuckGattService? = null
    private var deviceProfile: String? = null
    private var deviceAddress: String? = null
    private var maxSlotCount = 85
    private var currentCount = 0
    private var deviceDialog: AlertDialog? = null

    private enum class STATE {
        NONE, SCANNING, CONNECT, MISSING, TIMEOUT
    }

    private var noticeState = STATE.NONE

    private enum class SHEET {
        LOCKED, AMIIBO, MENU, WRITE
    }

    private val fragmentHandler = Handler(Looper.getMainLooper())
    private var flaskServerConn: ServiceConnection = object : ServiceConnection {
        var isServiceDiscovered = false
        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            val localBinder = binder as FlaskGattService.LocalBinder
            serviceFlask = localBinder.service.apply {
                if (initialize() && connect(deviceAddress)) {
                    setListener(object : FlaskGattService.BluetoothGattListener {
                        override fun onServicesDiscovered() {
                            isServiceDiscovered = true
                            onBottomSheetChanged(SHEET.MENU)
                            maxSlotCount = 85
                            requireView().post {
                                flaskSlotCount.maxValue = maxSlotCount
                                screenOptions?.isVisible = true
                                createBlank?.isVisible = true
                                requireView().findViewById<TextView>(
                                    R.id.hardware_info
                                ).text = deviceProfile
                            }
                            try {
                                setFlaskCharacteristicRX()
                                deviceAmiibo
                            } catch (uoe: UnsupportedOperationException) {
                                disconnectService()
                                Toasty(requireContext()).Short(R.string.device_invalid)
                            }
                        }

                        override fun onFlaskStatusChanged(jsonObject: JSONObject?) {
                            CoroutineScope(Dispatchers.Main).launch {
                                processDialog?.let {
                                    if (it.isShowing) it.dismiss()
                                }
                            }
                            deviceAmiibo
                        }

                        override fun onFlaskListRetrieved(jsonArray: JSONArray) {
                            CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
                                currentCount = jsonArray.length()
                                val flaskAmiibos: ArrayList<Amiibo?> = arrayListOf()
                                for (i in 0 until currentCount) {
                                    try {
                                        val amiibo = getAmiiboFromTail(
                                            jsonArray.getString(i).split("|")
                                        )
                                        flaskAmiibos.add(amiibo)
                                    } catch (ex: JSONException) {
                                        Debug.warn(ex)
                                    } catch (ex: NullPointerException) {
                                        Debug.warn(ex)
                                    }
                                }
                                flaskAdapter = FlaskSlotAdapter(
                                    settings, this@FlaskSlotFragment
                                ).also {
                                    it.setFlaskAmiibo(flaskAmiibos)
                                    withContext(Dispatchers.Main) {
                                        dismissSnackbarNotice(true)
                                    }
                                    flaskContent?.post {
                                        flaskContent?.adapter = it
                                    }
                                    if (currentCount > 0) {
                                        activeAmiibo
                                        it.notifyItemRangeInserted(0, currentCount)
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            amiiboTile?.isInvisible = true
                                            flaskButtonState
                                        }
                                    }
                                }
                            }
                        }

                        override fun onFlaskRangeRetrieved(jsonArray: JSONArray) {
                            CoroutineScope(Dispatchers.IO).launch {
                                val flaskAmiibos: ArrayList<Amiibo?> = arrayListOf()
                                for (i in 0 until jsonArray.length()) {
                                    try {
                                        val amiibo = getAmiiboFromTail(
                                            jsonArray.getString(i).split("|")
                                        )
                                        flaskAmiibos.add(amiibo)
                                    } catch (ex: JSONException) {
                                        Debug.warn(ex)
                                    } catch (ex: NullPointerException) {
                                        Debug.warn(ex)
                                    }
                                }
                                flaskAdapter?.run {
                                    addFlaskAmiibo(flaskAmiibos)
                                    notifyItemRangeInserted(currentCount, flaskAmiibos.size)
                                    currentCount = itemCount
                                }
                            }
                        }

                        override fun onFlaskActiveChanged(jsonObject: JSONObject?) {
                            if (null == jsonObject) return
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val name = jsonObject.getString("name")
                                    if ("undefined" == name) {
                                        resetActiveSlot()
                                        return@launch
                                    }
                                    val amiibo = getAmiiboFromTail(name.split("|"))
                                    val index = jsonObject.getString("index")
                                    getActiveAmiibo(amiibo, amiiboTile)
                                    if (bottomSheet?.state ==
                                        BottomSheetBehavior.STATE_COLLAPSED
                                    ) getActiveAmiibo(amiibo, amiiboCard)
                                    prefs.flaskActiveSlot(index.toInt())
                                    withContext(Dispatchers.Main) {
                                        flaskStats?.text =
                                            getString(R.string.flask_count, index, currentCount)
                                    }
                                    flaskButtonState
                                } catch (ex: JSONException) {
                                    Debug.warn(ex)
                                } catch (ex: NullPointerException) {
                                    Debug.warn(ex)
                                }
                            }
                        }

                        override fun onFlaskFilesDownload(dataString: String) {
                            try {
                                val tagData = dataString.toByteArray()
                            } catch (e: Exception) { e.printStackTrace() }
                        }

                        override fun onFlaskProcessFinish() {
                            CoroutineScope(Dispatchers.Main).launch {
                                processDialog?.let {
                                    if (it.isShowing) it.dismiss()
                                }
                            }
                        }

                        override fun onGattConnectionLost() {
                            fragmentHandler.postDelayed(
                                { showDisconnectNotice() }, TagMo.uiDelay.toLong()
                            )
                            CoroutineScope(Dispatchers.Main).launch {
                                bottomSheet?.setState(BottomSheetBehavior.STATE_COLLAPSED)
                            }
                            stopGattService()
                        }
                    })
                } else {
                    stopGattService()
                    Toasty(requireContext()).Short(R.string.device_invalid)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            stopGattService()
            if (!isServiceDiscovered) {
                showTimeoutNotice()
            }
        }
    }
    private var puckServerConn: ServiceConnection = object : ServiceConnection {
        var isServiceDiscovered = false
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as PuckGattService.LocalBinder
            servicePuck = localBinder.service.apply {
                if (initialize() && connect(deviceAddress)) {
                    setListener(object : PuckGattService.BluetoothGattListener {
                        override fun onServicesDiscovered() {
                            isServiceDiscovered = true
                            onBottomSheetChanged(SHEET.MENU)
                            maxSlotCount = 32
                            requireView().post {
                                flaskSlotCount.maxValue = maxSlotCount
                                screenOptions?.isGone = true
                                createBlank?.isGone = true
                                requireView().findViewById<TextView>(
                                    R.id.hardware_info
                                ).text = deviceProfile
                                flaskSlotCount.maxValue = maxSlotCount
                            }
                            try {
                                setPuckCharacteristicRX()
                                deviceAmiibo
                                // getDeviceSlots(32);
                            } catch (uoe: UnsupportedOperationException) {
                                disconnectService()
                                Toasty(requireContext()).Short(R.string.device_invalid)
                            }
                        }

                        override fun onPuckActiveChanged(slot: Int) {
                            CoroutineScope(Dispatchers.IO).launch {
                                flaskAdapter?.run {
                                    val amiibo = getItem(slot)
                                    getActiveAmiibo(amiibo, amiiboTile)
                                    if (bottomSheet?.state == BottomSheetBehavior.STATE_COLLAPSED)
                                        getActiveAmiibo(amiibo, amiiboCard)
                                    prefs.flaskActiveSlot(slot)
                                    flaskButtonState
                                    withContext(Dispatchers.Main) {
                                        flaskStats?.text = getString(
                                            R.string.flask_count, slot.toString(), currentCount
                                        )
                                    }
                                }
                            }
                        }

                        override fun onPuckListRetrieved(
                            slotData: ArrayList<ByteArray?>, active: Int
                        ) {
                            CoroutineScope(Dispatchers.IO).launch {
                                currentCount = slotData.size
                                val flaskAmiibos: ArrayList<Amiibo?> = arrayListOf()
                                for (i in 0 until currentCount) {
                                    if (slotData[i]?.isNotEmpty() == true) {
                                        val amiibo = getAmiiboFromHead(slotData[i])
                                        flaskAmiibos.add(amiibo)
                                    } else {
                                        flaskAmiibos.add(null)
                                    }
                                }
                                flaskAdapter = FlaskSlotAdapter(
                                    settings, this@FlaskSlotFragment
                                ).also {
                                    it.setFlaskAmiibo(flaskAmiibos)
                                    withContext(Dispatchers.Main) {
                                        dismissSnackbarNotice(true)
                                    }
                                    flaskContent?.post {
                                        flaskContent?.adapter = it
                                    }
                                    if (currentCount > 0) {
                                        it.notifyItemRangeInserted(0, currentCount)
                                        onPuckActiveChanged(active)
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            amiiboTile?.isInvisible = true
                                            flaskButtonState
                                        }
                                    }
                                }
                            }
                        }

                        override fun onPuckFilesDownload(tagData: ByteArray) {}
                        override fun onPuckProcessFinish() {
                            CoroutineScope(Dispatchers.Main).launch {
                                processDialog?.let {
                                    if (it.isShowing) it.dismiss()
                                }
                            }
                        }

                        override fun onGattConnectionLost() {
                            fragmentHandler.postDelayed(
                                { showDisconnectNotice() }, TagMo.uiDelay.toLong()
                            )
                            CoroutineScope(Dispatchers.Main).launch {
                                bottomSheet?.setState(BottomSheetBehavior.STATE_COLLAPSED)
                            }
                            stopGattService()
                        }
                    })
                } else {
                    stopGattService()
                    Toasty(requireActivity()).Short(R.string.device_invalid)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            stopGattService()
            if (!isServiceDiscovered) {
                showTimeoutNotice()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_flask_slot, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return
        
        val activity = requireActivity() as BrowserActivity

        val bitmapHeight = Resources.getSystem().displayMetrics.heightPixels / 4
        amiiboTile = view.findViewById<CardView>(R.id.active_tile_layout).apply {
            with (findViewById<AppCompatImageView>(R.id.imageAmiibo)) {
                amiiboTileTarget = object : CustomTarget<Bitmap?>() {
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        setImageResource(R.drawable.ic_no_image_60)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        setImageResource(R.drawable.ic_no_image_60)
                    }

                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap?>?) {
                        maxHeight = bitmapHeight
                        requestLayout()
                        setImageBitmap(resource)
                    }
                }
            }
        }
        amiiboCard = view.findViewById<CardView>(R.id.active_card_layout).apply {
            findViewById<View>(R.id.txtError)?.isGone = true
            findViewById<View>(R.id.txtPath)?.isGone = true
            with (findViewById<AppCompatImageView>(R.id.imageAmiibo)) {
                amiiboCardTarget = object : CustomTarget<Bitmap?>() {
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        setImageResource(0)
                        isGone = true
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        setImageResource(0)
                        isGone = true
                    }

                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap?>?) {
                        maxHeight = bitmapHeight
                        requestLayout()
                        setImageBitmap(resource)
                        isVisible = true
                    }
                }
            }
        }
        
        toolbar = view.findViewById(R.id.toolbar)

        settings = activity.settings ?: BrowserSettings().initialize()

        flaskContent = view.findViewById<RecyclerView>(R.id.flask_content).apply {
            if (prefs.softwareLayer()) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            layoutManager = if (settings.amiiboView == BrowserSettings.VIEW.IMAGE.value)
                GridLayoutManager(activity, activity.columnCount)
            else
                LinearLayoutManager(activity)
        }

        flaskStats = view.findViewById(R.id.flask_stats)
        switchMenuOptions = view.findViewById(R.id.switch_menu_btn)
        slotOptionsMenu = view.findViewById(R.id.slot_options_menu)

        createBlank = view.findViewById<AppCompatButton>(R.id.create_blank).apply {
            setOnClickListener { serviceFlask?.createBlankTag() }
        }

        screenOptions = view.findViewById(R.id.screen_options)

        val searchView = view.findViewById<SearchView>(R.id.amiibo_search)
        if (BuildConfig.WEAR_OS) {
            searchView.isGone = true
        } else {
            with (activity.getSystemService(Context.SEARCH_SERVICE) as SearchManager) {
                searchView.setSearchableInfo(getSearchableInfo(activity.componentName))
            }
            searchView.isSubmitButtonEnabled = false
            searchView.setIconifiedByDefault(false)
            val searchBar = searchView.findViewById<LinearLayout>(R.id.search_bar)
            searchBar.layoutParams.height = resources
                .getDimension(R.dimen.button_height_min).toInt()
            searchBar.gravity = Gravity.CENTER_VERTICAL
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    settings.query = query
                    settings.notifyChanges()
                    return false
                }

                override fun onQueryTextChange(query: String): Boolean {
                    settings.query = query
                    settings.notifyChanges()
                    return true
                }
            })
        }

        view.findViewById<AppCompatButton>(R.id.write_slot_file).apply {
            setOnClickListener {
                settings.addChangeListener(writeTagAdapter)
                onBottomSheetChanged(SHEET.WRITE)
                searchView.setQuery(settings.query, true)
                searchView.clearFocus()
                writeTagAdapter?.setListener(object : WriteTagAdapter.OnAmiiboClickListener {
                    override fun onAmiiboClicked(amiiboFile: AmiiboFile?) {
                        onBottomSheetChanged(SHEET.AMIIBO)
                        showProcessingNotice(true)
                        uploadAmiiboFile(amiiboFile)
                        settings.removeChangeListener(writeTagAdapter)
                    }

                    override fun onAmiiboImageClicked(amiiboFile: AmiiboFile?) {
                        handleImageClicked(amiiboFile)
                    }

                    override fun onAmiiboListClicked(amiiboList: ArrayList<AmiiboFile?>?) {}
                    override fun onAmiiboDataClicked(amiiboFile: AmiiboFile?, count: Int) {}
                })
                bottomSheet?.setState(BottomSheetBehavior.STATE_EXPANDED)
            }
        }

        writeSlots = view.findViewById<AppCompatButton>(R.id.write_slot_count).apply {
            text = getString(R.string.write_slots, 1)
            setOnClickListener {
                settings.addChangeListener(writeTagAdapter)
                onBottomSheetChanged(SHEET.WRITE)
                searchView.setQuery(settings.query, true)
                searchView.clearFocus()
                flaskSlotCount.value.let { count ->
                    writeTagAdapter?.setListener(object : WriteTagAdapter.OnAmiiboClickListener {
                        override fun onAmiiboClicked(amiiboFile: AmiiboFile?) {}
                        override fun onAmiiboImageClicked(amiiboFile: AmiiboFile?) {}
                        override fun onAmiiboListClicked(amiiboList: ArrayList<AmiiboFile?>?) {
                            if (!amiiboList.isNullOrEmpty()) writeAmiiboFileCollection(amiiboList)
                        }
                        override fun onAmiiboDataClicked(amiiboFile: AmiiboFile?, count: Int) {
                            amiiboFile?.let {
                                writeAmiiboCollection(it.withRandomSerials(keyManager, count))
                            }
                        }
                    }, count, writeSerials?.isChecked ?: false)
                }
                bottomSheet?.setState(BottomSheetBehavior.STATE_EXPANDED)
            }
        }

        writeSerials = view.findViewById(R.id.write_serial_fill)

        eraseSlots = view.findViewById<AppCompatButton>(R.id.erase_slot_count).apply {
            text = getString(R.string.erase_slots, 0)
            setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setMessage(R.string.gatt_erase_confirm)
                    .setPositiveButton(R.string.proceed) { _: DialogInterface?, _: Int ->
                        showProcessingNotice(false)
                        serviceFlask?.clearStorage(currentCount)
                    }
                    .setNegativeButton(R.string.cancel) { dialog: DialogInterface, _: Int ->
                        dialog.dismiss()
                    }
                    .show()
            }
        }

        flaskSlotCount = view.findViewById<NumberPicker>(R.id.number_picker_slot).apply {
            if (prefs.softwareLayer()) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            maxValue = maxSlotCount
            setOnValueChangedListener { _, _, newVal ->
                if (maxSlotCount - currentCount > 0)
                    writeSlots?.text = getString(R.string.write_slots, newVal)
            }
        }

        writeSlotsLayout = view.findViewById<LinearLayout>(R.id.write_list_slots).apply {
            if (prefs.softwareLayer()) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        view.findViewById<RecyclerView>(R.id.amiibo_files_list).apply {
            if (prefs.softwareLayer()) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            setHasFixedSize(true)
            layoutManager = if (settings.amiiboView == BrowserSettings.VIEW.IMAGE.value)
                GridLayoutManager(activity, activity.columnCount)
            else LinearLayoutManager(activity)
            writeTagAdapter = WriteTagAdapter(settings).also { adapter = it }
        }

        val toggle = view.findViewById<AppCompatImageView>(R.id.toggle)
        bottomSheet = BottomSheetBehavior.from(view.findViewById(R.id.bottom_sheet_slot)).apply {
            state = BottomSheetBehavior.STATE_COLLAPSED
            var slideHeight = 0F
            addBottomSheetCallback(object : BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                        if (writeSlotsLayout?.visibility == View.VISIBLE)
                            onBottomSheetChanged(SHEET.MENU)
                        toggle.setImageResource(R.drawable.ic_expand_less_24dp)
                        flaskContent?.setPadding(0, 0, 0, 0)
                    } else if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                        toggle.setImageResource(R.drawable.ic_expand_more_24dp)
                        flaskContent?.let {
                            val bottomHeight: Int = (view.measuredHeight - peekHeight)
                            it.setPadding(0, 0, 0, if (slideHeight > 0)
                                (bottomHeight * slideHeight).toInt() else 0
                            )
                        }

                    }
                }

                override fun onSlide(view: View, slideOffset: Float) { slideHeight = slideOffset }
            })
        }.also { bottomSheet ->
            setBottomSheetHidden(false)
            toggle.setOnClickListener {
                if (bottomSheet.state == BottomSheetBehavior.STATE_COLLAPSED) {
                    bottomSheet.setState(BottomSheetBehavior.STATE_EXPANDED)
                } else {
                    bottomSheet.setState(BottomSheetBehavior.STATE_COLLAPSED)
                }
            }
        }
        toggle.setImageResource(R.drawable.ic_expand_more_24dp)
        toolbar?.inflateMenu(R.menu.flask_menu)

        view.findViewById<View>(R.id.switch_devices).setOnClickListener {
            bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
            disconnectService()
            if (isBluetoothEnabled) selectBluetoothDevice()
        }
        switchMenuOptions?.setOnClickListener {
            if (slotOptionsMenu?.isShown == true) {
                onBottomSheetChanged(SHEET.AMIIBO)
            } else {
                onBottomSheetChanged(SHEET.MENU)
            }
            bottomSheet?.setState(BottomSheetBehavior.STATE_EXPANDED)
        }

        view.findViewById<View>(R.id.screen_layered)
            .setOnClickListener { serviceFlask?.setFlaskFace(false) }
        view.findViewById<View>(R.id.screen_stacked)
            .setOnClickListener { serviceFlask?.setFlaskFace(true) }
        flaskButtonState
    }

    private fun onBottomSheetChanged(sheet: SHEET) {
        bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
        bottomSheet?.isFitToContents = true
        requireActivity().runOnUiThread {
            when (sheet) {
                SHEET.LOCKED -> {
                    amiiboCard?.isGone = true
                    switchMenuOptions?.isGone = true
                    slotOptionsMenu?.isGone = true
                    writeSlotsLayout?.isGone = true
                }
                SHEET.AMIIBO -> {
                    amiiboCard?.isVisible = true
                    switchMenuOptions?.isVisible = true
                    slotOptionsMenu?.isGone = true
                    writeSlotsLayout?.isGone = true
                }
                SHEET.MENU -> {
                    amiiboCard?.isGone = true
                    switchMenuOptions?.isVisible = true
                    slotOptionsMenu?.isVisible = true
                    writeSlotsLayout?.isGone = true
                }
                SHEET.WRITE -> {
                    bottomSheet?.isFitToContents = false
                    amiiboCard?.isGone = true
                    switchMenuOptions?.isGone = true
                    slotOptionsMenu?.isGone = true
                    writeSlotsLayout?.isVisible = true
                }
            }
        }
    }

    fun setAmiiboInfoText(textView: TextView?, text: CharSequence?) {
        textView?.isVisible = true
        if (!text.isNullOrEmpty()) {
            textView?.text = text
            textView?.isEnabled = true
        } else {
            textView?.setText(R.string.unknown)
            textView?.isEnabled = false
        }
    }

    private val flaskButtonState: Unit
        get() {
            flaskContent?.post {
                val openSlots = maxSlotCount - currentCount
                flaskSlotCount.value = openSlots
                if (openSlots > 0) {
                    writeSlots?.isEnabled = true
                    writeSlots?.text = getString(R.string.write_slots, openSlots)
                } else {
                    writeSlots?.isEnabled = false
                    writeSlots?.text = getString(R.string.slots_full)
                }
                if (currentCount > 0) {
                    eraseSlots?.isEnabled = true
                    eraseSlots?.text = getString(R.string.erase_slots, currentCount)
                } else {
                    eraseSlots?.isEnabled = false
                    eraseSlots?.text = getString(R.string.slots_empty)
                }
            }
        }

    private fun resetActiveSlot() {
        flaskAdapter?.getItem(0).run {
            if (this is FlaskTag) {
                serviceFlask?.setActiveAmiibo(name, String(TagArray.longToBytes(id)))
            } else {
                this?.let { serviceFlask?.setActiveAmiibo(it.name, it.flaskTail) }
            }
        }
    }

    private fun getActiveAmiibo(active: Amiibo?, amiiboView: View?) {
        if (null == amiiboView) return
        val txtName = amiiboView.findViewById<TextView>(R.id.txtName)
        val txtTagId = amiiboView.findViewById<TextView>(R.id.txtTagId)
        val txtAmiiboSeries = amiiboView.findViewById<TextView>(R.id.txtAmiiboSeries)
        val txtAmiiboType = amiiboView.findViewById<TextView>(R.id.txtAmiiboType)
        val txtGameSeries = amiiboView.findViewById<TextView>(R.id.txtGameSeries)
        val imageAmiibo = amiiboView.findViewById<AppCompatImageView>(R.id.imageAmiibo)
        val txtUsageLabel = amiiboView.findViewById<TextView>(R.id.txtUsageLabel)
        amiiboView.post {
            val amiiboHexId: String
            val amiiboName: String?
            var amiiboSeries = ""
            var amiiboType = ""
            var gameSeries = ""
            var amiiboImageUrl: String? = null
            if (amiiboView === amiiboTile) amiiboView.isVisible = true
            if (null == active) {
                txtName.setText(R.string.no_tag_loaded)
                txtTagId?.isInvisible = true
                txtAmiiboSeries.isInvisible = true
                txtAmiiboType.isInvisible = true
                txtGameSeries.isInvisible = true
                if (amiiboView === amiiboCard) txtUsageLabel.isInvisible = true
            } else if (active is FlaskTag) {
                txtName.setText(R.string.blank_tag)
                txtTagId?.isInvisible = true
                txtAmiiboSeries.isInvisible = true
                txtAmiiboType.isInvisible = true
                txtGameSeries.isInvisible = true
                if (amiiboView === amiiboCard) txtUsageLabel.isInvisible = true
            } else {
                txtTagId?.isInvisible = false
                txtAmiiboSeries.isInvisible = false
                txtAmiiboType.isInvisible = false
                txtGameSeries.isInvisible = false
                if (amiiboView === amiiboCard) txtUsageLabel.isInvisible = false
                amiiboHexId = Amiibo.idToHex(active.id)
                amiiboName = active.name
                amiiboImageUrl = active.imageUrl
                active.amiiboSeries?.let { amiiboSeries = it.name }
                active.amiiboType?.let { amiiboType = it.name }
                active.gameSeries?.let { gameSeries = it.name }
                setAmiiboInfoText(txtName, amiiboName)
                setAmiiboInfoText(txtTagId, amiiboHexId)
                setAmiiboInfoText(txtAmiiboSeries, amiiboSeries)
                setAmiiboInfoText(txtAmiiboType, amiiboType)
                setAmiiboInfoText(txtGameSeries, gameSeries)
                if (hasSpoofData(amiiboHexId)) txtTagId.isEnabled = false
            }

            imageAmiibo?.let {
                if (amiiboView === amiiboCard && null == amiiboImageUrl) {
                    it.setImageResource(0)
                    it.isInvisible = true
                } else if (amiiboView !== amiiboTile || null != amiiboImageUrl) {
                    if (amiiboView === amiiboCard) {
                        it.setImageResource(0)
                        it.isGone = true
                    }
                    if (!amiiboImageUrl.isNullOrEmpty()) {
                        GlideApp.with(it).clear(it)
                        GlideApp.with(it).asBitmap().load(amiiboImageUrl).into(
                            if (amiiboView === amiiboCard) amiiboCardTarget else amiiboTileTarget
                        )
                    }
                    it.setOnClickListener {
                        startActivity(Intent(requireContext(), ImageActivity::class.java)
                            .putExtras(Bundle().apply {
                                putLong(NFCIntent.EXTRA_AMIIBO_ID, active!!.id)
                            })
                        )
                    }
                }
            }
        }
    }

    private fun getAmiiboFromTail(name: List<String>): Amiibo? {
        when {
            name.size < 2 -> return null
            name[0].startsWith("New Tag") || name[1].isEmpty() -> return FlaskTag(name)
            else -> {
                var amiiboManager: AmiiboManager?
                try {
                    amiiboManager = getAmiiboManager(requireContext().applicationContext)
                } catch (e: IOException) {
                    Debug.warn(e)
                    amiiboManager = null
                    Toasty(requireActivity()).Short(R.string.amiibo_info_parse_error)
                } catch (e: JSONException) {
                    Debug.warn(e)
                    amiiboManager = null
                    Toasty(requireActivity()).Short(R.string.amiibo_info_parse_error)
                } catch (e: ParseException) {
                    Debug.warn(e)
                    amiiboManager = null
                    Toasty(requireActivity()).Short(R.string.amiibo_info_parse_error)
                }
                var selectedAmiibo: Amiibo? = null
                amiiboManager?.let {
                    val matches : ArrayList<Amiibo> = arrayListOf()
                    run breaking@{
                        it.amiibos.values.forEach { amiibo ->
                            if (name[1] == amiibo.flaskTail) {
                                if (amiibo.flaskName == name[0]) {
                                    selectedAmiibo = amiibo
                                    matches.clear()
                                    return@breaking
                                } else {
                                    matches.add(amiibo)
                                }
                            }
                        }
                        selectedAmiibo = matches.find {
                            null == it.flaskName
                        }?.apply { flaskName = name[0] }
                    }
                    if (null == selectedAmiibo && matches.isNotEmpty())
                        selectedAmiibo = matches[0]
                }
                return selectedAmiibo
            }
        }
    }

    private fun getAmiiboFromHead(tagData: ByteArray?): Amiibo? {
        var amiiboManager: AmiiboManager?
        try {
            amiiboManager = getAmiiboManager(requireContext().applicationContext)
        } catch (e: IOException) {
            Debug.warn(e)
            amiiboManager = null
            Toasty(requireActivity()).Short(R.string.amiibo_info_parse_error)
        } catch (e: JSONException) {
            Debug.warn(e)
            amiiboManager = null
            Toasty(requireActivity()).Short(R.string.amiibo_info_parse_error)
        } catch (e: ParseException) {
            Debug.warn(e)
            amiiboManager = null
            Toasty(requireActivity()).Short(R.string.amiibo_info_parse_error)
        }
        if (Thread.currentThread().isInterrupted) return null
        var selectedAmiibo: Amiibo? = null
        amiiboManager?.let {
            try {
                val headData = ByteBuffer.wrap(tagData!!)
                val amiiboId = headData.getLong(0x28)
                selectedAmiibo = it.amiibos[amiiboId]
            } catch (e: Exception) { Debug.info(e) }
        }
        return selectedAmiibo
    }

    @SuppressLint("InflateParams")
    private fun displayScanResult(
        deviceDialog: AlertDialog, device: BluetoothDevice, deviceType: Int
    ) : View {
        val item = this.layoutInflater.inflate(R.layout.device_bluetooth, null)
        item.findViewById<TextView>(R.id.device_name).text = device.name
        item.findViewById<TextView>(R.id.device_address).text =
            requireActivity().getString(R.string.device_address, device.address)
        item.findViewById<View>(R.id.connect_flask).setOnClickListener {
            deviceDialog.dismiss()
            deviceProfile = device.name
            deviceAddress = device.address
            dismissGattDiscovery()
            showConnectionNotice()
            startFlaskService()
        }
        item.findViewById<View>(R.id.connect_flask).isEnabled = deviceType != 2
        item.findViewById<View>(R.id.connect_puck).setOnClickListener {
            deviceDialog.dismiss()
            deviceProfile = device.name
            deviceAddress = device.address
            dismissGattDiscovery()
            showConnectionNotice()
            startPuckService()
        }
        item.findViewById<View>(R.id.connect_puck).isEnabled = deviceType != 1
        return item
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun scanBluetoothServices(deviceDialog: AlertDialog) {
        mBluetoothAdapter = mBluetoothAdapter
            ?: bluetoothHandler?.getBluetoothAdapter(requireContext())
        if (null == mBluetoothAdapter) {
            setBottomSheetHidden(true)
            Toasty(requireActivity()).Long(R.string.fail_bluetooth_adapter)
            return
        }
        showScanningNotice()
        deviceProfile = null
        val devices: ArrayList<BluetoothDevice> = arrayListOf()
        if (Version.isLollipop) {
            val scanner = mBluetoothAdapter?.bluetoothLeScanner
            val settings = ScanSettings.Builder().setScanMode(
                ScanSettings.SCAN_MODE_LOW_LATENCY
            ).build()
            val filterFlask = ScanFilter.Builder().setServiceUuid(
                ParcelUuid(FlaskGattService.FlaskNUS)
            ).build()
            scanCallbackFlaskLP = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    super.onScanResult(callbackType, result)
                    if (!devices.contains(result.device)) {
                        devices.add(result.device)
                        deviceDialog.findViewById<LinearLayout>(R.id.bluetooth_result)?.addView(
                            displayScanResult(deviceDialog, result.device, 1)
                        )
                    }
                }
            }
            scanner?.startScan(listOf(filterFlask), settings, scanCallbackFlaskLP)
            val filterPuck = ScanFilter.Builder().setServiceUuid(
                ParcelUuid(PuckGattService.PuckNUS)
            ).build()
            scanCallbackPuckLP = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    super.onScanResult(callbackType, result)
                    if (!devices.contains(result.device)) {
                        devices.add(result.device)
                        deviceDialog.findViewById<LinearLayout>(R.id.bluetooth_result)?.addView(
                            displayScanResult(deviceDialog, result.device, 2)
                        )
                    }
                }
            }
            scanner?.startScan(listOf(filterPuck), settings, scanCallbackPuckLP)
        } else @Suppress("DEPRECATION") {
            scanCallbackFlask =
                LeScanCallback { bluetoothDevice: BluetoothDevice, _: Int, _: ByteArray? ->
                    if (!devices.contains(bluetoothDevice)) {
                        devices.add(bluetoothDevice)
                        deviceDialog.findViewById<LinearLayout>(R.id.bluetooth_result)?.addView(
                            displayScanResult(deviceDialog, bluetoothDevice, 1)
                        )
                    }
                }
            mBluetoothAdapter?.startLeScan(arrayOf(FlaskGattService.FlaskNUS), scanCallbackFlask)
            scanCallbackPuck =
                LeScanCallback { bluetoothDevice: BluetoothDevice, _: Int, _: ByteArray? ->
                    if (!devices.contains(bluetoothDevice)) {
                        devices.add(bluetoothDevice)
                        deviceDialog.findViewById<LinearLayout>(R.id.bluetooth_result)?.addView(
                            displayScanResult(deviceDialog, bluetoothDevice, 2)
                        )
                    }
                }
            mBluetoothAdapter?.startLeScan(arrayOf(PuckGattService.PuckNUS), scanCallbackPuck)
        }
        fragmentHandler.postDelayed({
            if (null == deviceProfile) {
                dismissGattDiscovery()
                showTimeoutNotice()
            }
        }, 30000)
    }

    @SuppressLint("MissingPermission")
    private fun selectBluetoothDevice() {
        if (mBluetoothAdapter?.isEnabled != true) {
            Toasty(requireActivity()).Long(R.string.fail_bluetooth_adapter)
            return
        }
        if (deviceDialog?.isShowing == true) return
        val view = this.layoutInflater.inflate(R.layout.dialog_devices, null) as LinearLayout
        view.findViewById<AppCompatButton>(R.id.purchase_flask).setOnClickListener {
            startActivity(Intent(
                Intent.ACTION_VIEW, Uri.parse("https://www.bluuplabs.com/flask/")
            ))
        }
        deviceDialog = AlertDialog.Builder(requireActivity()).setView(view).show().apply {
            mBluetoothAdapter?.bondedDevices?.forEach { device ->
                val deviceType = if (device.name.lowercase().startsWith("flask")) 1 else 0
                view.findViewById<LinearLayout>(R.id.bluetooth_paired)?.addView(
                    displayScanResult(this, device, deviceType)
                )
            }
            scanBluetoothServices(this)
        }
    }

    private fun writeAmiiboCollection(bytesList: ArrayList<AmiiboData?>) {
        settings.removeChangeListener(writeTagAdapter)
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.gatt_write_confirm)
            .setPositiveButton(R.string.proceed) { dialog: DialogInterface, _: Int ->
                showProcessingNotice(true)
                bytesList.forEachIndexed { i, byte ->
                    fragmentHandler.postDelayed({
                        uploadAmiiboData(byte, i == bytesList.size - 1)
                    }, 30L * i)
                }
                onBottomSheetChanged(SHEET.MENU)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog: DialogInterface, _: Int ->
                onBottomSheetChanged(SHEET.MENU)
                dialog.dismiss()
            }
            .show()
    }

    private fun writeAmiiboFileCollection(amiiboList: ArrayList<AmiiboFile?>) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.gatt_write_confirm)
            .setPositiveButton(R.string.proceed) { dialog: DialogInterface, _: Int ->
                showProcessingNotice(true)
                amiiboList.forEachIndexed { i, file ->
                    fragmentHandler.postDelayed({
                        uploadAmiiboFile(file, i == amiiboList.size - 1)
                    }, 30L * i)
                }
                onBottomSheetChanged(SHEET.MENU)
                settings.removeChangeListener(writeTagAdapter)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog: DialogInterface, _: Int ->
                onBottomSheetChanged(SHEET.MENU)
                settings.removeChangeListener(writeTagAdapter)
                dialog.dismiss()
            }
            .show()
    }

    private fun uploadAmiiboData(amiiboData: AmiiboData?, complete: Boolean = true) {
        var amiibo: Amiibo? = null
        settings.amiiboManager?.let {
            try {
                val amiiboId = Amiibo.dataToId(amiiboData?.array)
                amiibo = it.amiibos[amiiboId]
                if (null == amiibo) amiibo = Amiibo(it, amiiboId, null, null)
            } catch (e: Exception) { Debug.warn(e) }
        }
        amiibo?.let {
            amiiboData?.array?.let { data ->
                serviceFlask?.uploadAmiiboFile(
                    data, it, flaskAdapter?.getDuplicates(it) ?: 0, complete
                )
                servicePuck?.uploadSlotAmiibo(data, flaskSlotCount.value - 1)
            }
        }
    }

    private fun uploadAmiiboFile(amiiboFile: AmiiboFile?, complete: Boolean = true) {
        amiiboFile?.let { file ->
            var amiibo: Amiibo? = null
            settings.amiiboManager?.let {
                try {
                    val amiiboId = Amiibo.dataToId(file.data)
                    amiibo = it.amiibos[amiiboId]
                    if (null == amiibo) amiibo = Amiibo(it, amiiboId, null, null)
                } catch (e: Exception) { Debug.warn(e) }
            }
            amiibo?.let {
                file.data?.let { data ->
                    serviceFlask?.uploadAmiiboFile(
                        data, it, flaskAdapter?.getDuplicates(it) ?: 0, complete
                    )
                    servicePuck?.uploadSlotAmiibo(data, flaskSlotCount.value - 1)
                }
            }
        }
    }

    private fun setBottomSheetHidden(hidden: Boolean) {
        bottomSheet?.isHideable = hidden
        if (hidden)
            bottomSheet?.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun dismissSnackbarNotice(finite: Boolean = false) {
        if (finite) noticeState = STATE.NONE
        if (statusBar?.isShown == true) statusBar?.dismiss()
    }

    private fun showScanningNotice() {
        dismissSnackbarNotice()
        noticeState = STATE.SCANNING
        if (isFragmentVisible) {
            statusBar = IconifiedSnackbar(requireActivity()).buildSnackbar(
                R.string.flask_scanning,
                R.drawable.ic_bluetooth_searching_24dp, Snackbar.LENGTH_INDEFINITE
            ).also {
                it.show()
                it.view.keepScreenOn = true
            }
        }
    }

    private fun showConnectionNotice() {
        dismissSnackbarNotice()
        noticeState = STATE.CONNECT
        if (isFragmentVisible) {
            statusBar = IconifiedSnackbar(requireActivity()).buildSnackbar(
                R.string.flask_located,
                R.drawable.ic_bluup_flask_24dp, Snackbar.LENGTH_INDEFINITE
            ).also {
                it.show()
                it.view.keepScreenOn = true
            }
        }
    }

    private fun showDisconnectNotice() {
        dismissSnackbarNotice()
        noticeState = STATE.MISSING
        if (isFragmentVisible) {
            statusBar = IconifiedSnackbar(requireActivity()).buildSnackbar(
                R.string.flask_disconnect,
                R.drawable.ic_bluetooth_searching_24dp, Snackbar.LENGTH_INDEFINITE
            ).also { status ->
                status.setAction(R.string.scan) {
                    selectBluetoothDevice()
                    status.dismiss()
                }
                status.show()
            }
        }
    }

    private fun showProcessingNotice(upload: Boolean) {
        val builder = AlertDialog.Builder(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_process, null)
        view.findViewById<TextView>(R.id.process_text).setText(
            if (upload) R.string.flask_upload else R.string.flask_remove
        )
        builder.setView(view)
        processDialog = builder.create().also {
            it.show()
            it.window?.decorView?.keepScreenOn = true
        }
    }

    private fun showTimeoutNotice() {
        dismissSnackbarNotice()
        noticeState = STATE.TIMEOUT
        if (isFragmentVisible) {
            statusBar = IconifiedSnackbar(requireActivity()).buildSnackbar(
                R.string.flask_missing,
                R.drawable.ic_bluup_flask_24dp,
                Snackbar.LENGTH_INDEFINITE
            ).also { status ->
                status.setAction(R.string.retry) {
                    selectBluetoothDevice()
                    status.dismiss()
                }
                status.show()
            }
        }
    }

    private fun startFlaskService() {
        val service = Intent(requireContext(), FlaskGattService::class.java)
        requireContext().startService(service)
        requireContext().bindService(service, flaskServerConn, Context.BIND_AUTO_CREATE)
    }

    private fun startPuckService() {
        val service = Intent(requireContext(), PuckGattService::class.java)
        requireContext().startService(service)
        requireContext().bindService(service, puckServerConn, Context.BIND_AUTO_CREATE)
    }

    fun disconnectService() {
        dismissSnackbarNotice(true)
        serviceFlask?.disconnect() ?: servicePuck?.disconnect() ?: stopGattService()
    }

    fun stopGattService() {
        onBottomSheetChanged(SHEET.LOCKED)
        deviceAddress = null
        try {
            requireContext().unbindService(flaskServerConn)
            requireContext().stopService(Intent(requireContext(), FlaskGattService::class.java))
        } catch (ignored: IllegalArgumentException) {
        }
        try {
            requireContext().unbindService(flaskServerConn)
            requireContext().stopService(Intent(requireContext(), FlaskGattService::class.java))
        } catch (ignored: IllegalArgumentException) { }
    }

    @SuppressLint("MissingPermission")
    private fun dismissGattDiscovery() {
        mBluetoothAdapter = mBluetoothAdapter
            ?: bluetoothHandler?.getBluetoothAdapter(requireContext())
        mBluetoothAdapter?.let { adapter ->
            if (!adapter.isEnabled) return
            if (Version.isLollipop) {
                scanCallbackFlaskLP?.let {
                    adapter.bluetoothLeScanner.stopScan(it)
                    adapter.bluetoothLeScanner.flushPendingScanResults(it)
                }
                scanCallbackPuckLP?.let {
                    adapter.bluetoothLeScanner.stopScan(it)
                    adapter.bluetoothLeScanner.flushPendingScanResults(it)
                }
            } else @Suppress("DEPRECATION") {
                scanCallbackFlask?.let { adapter.stopLeScan(it) }
                scanCallbackPuck?.let { adapter.stopLeScan(it) }
            }
        }
    }

    private fun handleImageClicked(amiiboFile: AmiiboFile?) {
        amiiboFile?.let {
            this.startActivity(Intent(requireContext(), ImageActivity::class.java).apply {
                putExtras(Bundle().apply { putLong(NFCIntent.EXTRA_AMIIBO_ID, it.id) })
            })
        }
    }

    private val isBluetoothEnabled: Boolean
        get() {
            if (mBluetoothAdapter?.isEnabled == true) return true
            context?.run {
                bluetoothHandler = bluetoothHandler ?: BluetoothHandler(
                    this, requireActivity().activityResultRegistry,
                    this@FlaskSlotFragment
                )
                bluetoothHandler?.requestPermissions(requireActivity())
            } ?: fragmentHandler.postDelayed({ isBluetoothEnabled }, 125)
            return false
        }

    fun delayedBluetoothEnable() {
        fragmentHandler.postDelayed({ isBluetoothEnabled }, 125)
    }

    override fun onPause() {
        isFragmentVisible = false
        dismissSnackbarNotice()
        if (noticeState == STATE.SCANNING) dismissGattDiscovery()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            dismissGattDiscovery()
        } catch (ignored: NullPointerException) { }
        disconnectService()
        bluetoothHandler?.unregisterResultContracts()
        super.onDestroy()
    }

    private fun onFragmentLoaded() {
        if (statusBar?.isShown != true) {
            fragmentHandler.postDelayed({
                when (noticeState) {
                    STATE.SCANNING, STATE.TIMEOUT -> {
                        if (isBluetoothEnabled) {
                            showScanningNotice()
                            selectBluetoothDevice()
                        }
                    }
                    STATE.CONNECT -> showConnectionNotice()
                    STATE.MISSING -> showDisconnectNotice()
                    else -> {}
                }
            }, TagMo.uiDelay.toLong())
            setBottomSheetHidden(false)
            onBottomSheetChanged(if (null == deviceAddress) SHEET.LOCKED else SHEET.MENU)
        }
    }

    override fun onResume() {
        isFragmentVisible = true
        super.onResume()
        onFragmentLoaded()
    }

    override fun onAmiiboClicked(amiibo: Amiibo?, position: Int) {
        getActiveAmiibo(amiibo, amiiboCard)
        onBottomSheetChanged(SHEET.AMIIBO)
        bottomSheet?.state = BottomSheetBehavior.STATE_EXPANDED
        if (amiibo is FlaskTag) {
            val amiiboName = amiibo.flaskName ?: amiibo.name
            toolbar?.menu?.findItem(R.id.mnu_backup)?.isVisible = false
            toolbar?.setOnMenuItemClickListener { item: MenuItem ->
                if (item.itemId == R.id.mnu_activate) {
                    serviceFlask?.setActiveAmiibo(amiiboName, amiibo.flaskTail)
                    servicePuck?.setActiveSlot(position)
                    return@setOnMenuItemClickListener true
                } else if (item.itemId == R.id.mnu_delete) {
                    serviceFlask?.deleteAmiibo(amiiboName, amiibo.flaskTail)
                    bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
                    return@setOnMenuItemClickListener true
                }
                false
            }
        } else amiibo?.let {
            val amiiboName = it.flaskName ?: it.name
            toolbar?.menu?.findItem(R.id.mnu_backup)?.isVisible = true
            toolbar?.setOnMenuItemClickListener { item: MenuItem ->
                when (item.itemId) {
                    R.id.mnu_activate -> {
                        serviceFlask?.setActiveAmiibo(amiiboName, it.flaskTail)
                        servicePuck?.setActiveSlot(position)
                        return@setOnMenuItemClickListener true
                    }
                    R.id.mnu_delete -> {
                        serviceFlask?.deleteAmiibo(amiiboName, it.flaskTail)
                        bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
                        return@setOnMenuItemClickListener true
                    }
                    R.id.mnu_backup -> {
                        serviceFlask?.downloadAmiibo(amiiboName, it.flaskTail)
                        bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
                        return@setOnMenuItemClickListener true
                    }
                    else -> false
                }
            }
        }
    }

    override fun onAmiiboImageClicked(amiibo: Amiibo?) {
        amiibo?.let {
            this.startActivity(Intent(requireContext(), ImageActivity::class.java)
                .putExtras(Bundle().apply { putLong(NFCIntent.EXTRA_AMIIBO_ID, it.id) })
            )
        }
    }

    override fun onPermissionsFailed() {
        this.mBluetoothAdapter = null
        setBottomSheetHidden(true)
        Toasty(requireActivity()).Long(R.string.fail_permissions)
    }

    override fun onAdapterMissing() {
        this.mBluetoothAdapter = null
        noticeState = STATE.MISSING
        setBottomSheetHidden(true)
        Toasty(requireActivity()).Long(R.string.fail_bluetooth_adapter)
    }

    override fun onAdapterRestricted() {
        delayedBluetoothEnable()
    }

    override fun onAdapterEnabled(adapter: BluetoothAdapter?) {
        this.mBluetoothAdapter = adapter
        setBottomSheetHidden(false)
        selectBluetoothDevice()
    }
}