package xyz.zedler.patrick.grocy;

/*
    This file is part of Grocy Android.

    Grocy Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Grocy Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Grocy Android.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2020 by Patrick Zedler & Dominic Zedler
*/

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.android.volley.VolleyError;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

import xyz.zedler.patrick.grocy.adapter.ShoppingItemAdapter;
import xyz.zedler.patrick.grocy.adapter.ShoppingPlaceholderAdapter;
import xyz.zedler.patrick.grocy.animator.ItemAnimator;
import xyz.zedler.patrick.grocy.database.AppDatabase;
import xyz.zedler.patrick.grocy.databinding.ActivityShoppingBinding;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.ShoppingListsBottomSheetDialogFragment;
import xyz.zedler.patrick.grocy.helper.DownloadHelper;
import xyz.zedler.patrick.grocy.helper.LoadOfflineDataShoppingListHelper;
import xyz.zedler.patrick.grocy.helper.ShoppingListHelper;
import xyz.zedler.patrick.grocy.helper.StoreOfflineDataShoppingListHelper;
import xyz.zedler.patrick.grocy.model.GroupedListItem;
import xyz.zedler.patrick.grocy.model.Product;
import xyz.zedler.patrick.grocy.model.ProductGroup;
import xyz.zedler.patrick.grocy.model.QuantityUnit;
import xyz.zedler.patrick.grocy.model.ShoppingList;
import xyz.zedler.patrick.grocy.model.ShoppingListItem;
import xyz.zedler.patrick.grocy.util.Constants;
import xyz.zedler.patrick.grocy.util.NetUtil;

public class ShoppingActivity extends AppCompatActivity implements
        ShoppingItemAdapter.ShoppingListItemSpecialAdapterListener,
        LoadOfflineDataShoppingListHelper.AsyncResponse,
        StoreOfflineDataShoppingListHelper.AsyncResponse {

    private final static String TAG = ShoppingActivity.class.getSimpleName();

    private SharedPreferences sharedPrefs;
    private DownloadHelper downloadHelper;
    private Timer timer;
    private AppDatabase database;
    private NetUtil netUtil;

    private ArrayList<ShoppingListItem> shoppingListItems;
    private ArrayList<ShoppingListItem> shoppingListItemsSelected;
    private ArrayList<ShoppingList> shoppingLists;
    private ArrayList<ProductGroup> productGroups;
    private ArrayList<QuantityUnit> quantityUnits;
    private ArrayList<Product> products;
    private ArrayList<GroupedListItem> groupedListItems;
    private HashMap<Integer, ShoppingList> shoppingListHashMap;

    private int selectedShoppingListId = 1;
    private boolean showOffline;
    private boolean isDataStored;
    private boolean debug;
    private Date lastSynced;
    private TimerTask timerTask;

    private ActivityShoppingBinding binding;
    private ShoppingItemAdapter shoppingItemAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // PREFERENCES

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        debug = sharedPrefs.getBoolean(Constants.PREF.DEBUG, false);

        // UTILS

        netUtil = new NetUtil(this);

        // DATABASE

        database = AppDatabase.getAppDatabase(getApplicationContext());

        // WEB

        downloadHelper = new DownloadHelper(
                this,
                TAG,
                this::onDownloadError,
                this::onQueueEmpty
        );

        // INITIALIZE VARIABLES

        shoppingLists = new ArrayList<>();
        shoppingListItems = new ArrayList<>();
        shoppingListItemsSelected = new ArrayList<>();
        quantityUnits = new ArrayList<>();
        products = new ArrayList<>();
        productGroups = new ArrayList<>();
        groupedListItems = new ArrayList<>();
        shoppingListHashMap = new HashMap<>();

        int lastId = sharedPrefs.getInt(Constants.PREF.SHOPPING_LIST_LAST_ID, -1);
        if(lastId != -1) selectedShoppingListId = lastId;

        // VIEWS

        binding = ActivityShoppingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.frameShoppingClose.setOnClickListener(v -> onBackPressed());
        binding.textTitle.setOnClickListener(v -> showShoppingListsBottomSheet());
        binding.buttonLists.setOnClickListener(v -> showShoppingListsBottomSheet());

        binding.swipe.setProgressBackgroundColorSchemeColor(
                ContextCompat.getColor(this, R.color.surface)
        );
        binding.swipe.setColorSchemeColors(
                ContextCompat.getColor(this, R.color.secondary)
        );
        binding.swipe.setOnRefreshListener(this::refresh);

        binding.recycler.setLayoutManager(
                new LinearLayoutManager(
                        this,
                        LinearLayoutManager.VERTICAL,
                        false
                )
        );
        binding.recycler.setItemAnimator(new ItemAnimator());
        binding.recycler.setAdapter(new ShoppingPlaceholderAdapter());

        // UI

        getWindow().setStatusBarColor(
                ContextCompat.getColor(
                        this,
                        Build.VERSION.SDK_INT <= Build.VERSION_CODES.M
                                ? R.color.status_bar_lollipop
                                : R.color.primary
                )
        );
        getWindow().setNavigationBarColor(
                ContextCompat.getColor(this, R.color.background_dark)
        );

        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        timer.cancel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        int seconds = sharedPrefs.getInt(
                Constants.PREF.SHOPPING_MODE_UPDATE_INTERVAL,
                10
        );
        if(seconds == 0) return;
        timer = new Timer();
        initTimerTask();
        timer.schedule(timerTask, 1000, seconds*1000);
    }

    private void load() {
        if(netUtil.isOnline()) {
            downloadFull();
        } else {
            loadOfflineData();
        }
    }

    public void refresh() {
        if(netUtil.isOnline()) {
            downloadFull();
            timer.cancel();
            int seconds = sharedPrefs.getInt(
                    Constants.PREF.SHOPPING_MODE_UPDATE_INTERVAL,
                    10
            );
            if(seconds == 0) return;
            initTimerTask();
            timer = new Timer();
            timer.schedule(timerTask, seconds*1000, seconds*1000);
        } else {
            binding.swipe.setRefreshing(false);
            loadOfflineData();
            showMessage(getString(R.string.msg_no_connection));
        }
    }

    private void downloadFull() {
        binding.swipe.setRefreshing(true);
        downloadHelper.downloadQuantityUnits(quantityUnits -> this.quantityUnits = quantityUnits);
        downloadHelper.downloadProducts(products -> this.products = products);
        downloadHelper.downloadProductGroups(productGroups -> this.productGroups = productGroups);
        downloadHelper.downloadShoppingListItems(listItems -> this.shoppingListItems = listItems);
        downloadHelper.downloadShoppingLists(shoppingLists -> {
            this.shoppingLists = shoppingLists;
            changeAppBarTitle();
            if(shoppingLists.size() == 1) binding.buttonLists.setVisibility(View.GONE);
        });
    }

    private void downloadShoppingListItems() {
        downloadHelper.downloadShoppingListItems(listItems -> this.shoppingListItems = listItems);
    }

    private void onQueueEmpty() {
        if(showOffline) showOffline = false;

        shoppingListItemsSelected = new ArrayList<>();
        ArrayList<Integer> allUsedProductIds = new ArrayList<>();  // for database preparing
        for(ShoppingListItem shoppingListItem : shoppingListItems) {
            if(shoppingListItem.getProductId() != null) {
                allUsedProductIds.add(Integer.parseInt(shoppingListItem.getProductId()));
            }
            if(shoppingListItem.getShoppingListId() != selectedShoppingListId) continue;
            if(shoppingListItem.isUndone()) {
                shoppingListItemsSelected.add(shoppingListItem);
            }
        }

        if(!isDataStored) {
            lastSynced = Calendar.getInstance().getTime();
            // sync modified data and store new data
            new StoreOfflineDataShoppingListHelper(
                    AppDatabase.getAppDatabase(getApplicationContext()),
                    this,
                    true,
                    shoppingLists,
                    shoppingListItems,
                    productGroups,
                    quantityUnits,
                    products,
                    allUsedProductIds
            ).execute();
        } else {
            isDataStored = false;

            // set product in shoppingListItem
            HashMap<Integer, Product> productHashMap = new HashMap<>();
            for(Product p : products) productHashMap.put(p.getId(), p);
            for(ShoppingListItem shoppingListItem : shoppingListItemsSelected) {
                if(shoppingListItem.getProductId() == null) continue;
                shoppingListItem.setProduct(
                        productHashMap.get(Integer.parseInt(shoppingListItem.getProductId()))
                );
            }

            binding.swipe.setRefreshing(false);
            groupItems();
        }
    }

    private void onDownloadError(VolleyError error) {
        binding.swipe.setRefreshing(false);
        loadOfflineData();
    }

    private void loadOfflineData() {
        if(!showOffline) {
            showOffline = true;
            if(debug) Log.i(TAG, "loadOfflineData: you are now offline");
            new LoadOfflineDataShoppingListHelper(
                    AppDatabase.getAppDatabase(getApplicationContext()),
                    this
            ).execute();
        }
        lastSynced = null;
    }

    @Override
    public void prepareOfflineData(
            ArrayList<ShoppingListItem> shoppingListItems,
            ArrayList<ShoppingList> shoppingLists,
            ArrayList<ProductGroup> productGroups,
            ArrayList<QuantityUnit> quantityUnits
    ) { // for offline mode
        this.shoppingListItems = shoppingListItems;
        this.shoppingLists = shoppingLists;
        this.productGroups = productGroups;
        this.quantityUnits = quantityUnits;

        shoppingListItemsSelected = new ArrayList<>();

        for(ShoppingListItem shoppingListItem : shoppingListItems) {
            if(shoppingListItem.getShoppingListId() != selectedShoppingListId) continue;
            if(shoppingListItem.isUndone()) {
                shoppingListItemsSelected.add(shoppingListItem);
            }
        }

        changeAppBarTitle();
        if(shoppingLists.size() == 1) binding.buttonLists.setVisibility(View.GONE);

        groupItems();
    }

    private void groupItems() {
        groupedListItems = ShoppingListHelper.groupItems(
                this,
                shoppingListItemsSelected,
                productGroups,
                shoppingLists,
                selectedShoppingListId,
                true
        );
        refreshAdapter(
                new ShoppingItemAdapter(
                        this,
                        groupedListItems,
                        quantityUnits,
                        this
                )
        );
    }

    private void refreshAdapter(ShoppingItemAdapter adapter) {
        shoppingItemAdapter = adapter;
        binding.recycler.animate().alpha(0).setDuration(150).withEndAction(() -> {
            binding.recycler.setAdapter(adapter);
            binding.recycler.animate().alpha(1).setDuration(150).start();
        }).start();
    }

    @Override
    public void syncItems(
            ArrayList<ShoppingListItem> itemsToSync,
            ArrayList<ShoppingList> shoppingLists,
            ArrayList<ShoppingListItem> shoppingListItems,
            ArrayList<ProductGroup> productGroups,
            ArrayList<QuantityUnit> quantityUnits,
            ArrayList<Product> products,
            ArrayList<Integer> usedProductIds,
            HashMap<Integer, ShoppingListItem> serverItemHashMap
    ) {
        downloadHelper.setOnQueueEmptyListener(() -> {
            showMessage(getString(R.string.msg_synced));
            new StoreOfflineDataShoppingListHelper(
                    AppDatabase.getAppDatabase(getApplicationContext()),
                    this,
                    false,
                    shoppingLists,
                    shoppingListItems,
                    productGroups,
                    quantityUnits,
                    products,
                    usedProductIds
            ).execute();
            downloadHelper.setOnQueueEmptyListener(this::onQueueEmpty);
        });
        for(ShoppingListItem itemToSync : itemsToSync) {
            JSONObject body = new JSONObject();
            try {
                body.put("done", itemToSync.getDone());
            } catch (JSONException e) {
                if(debug) Log.e(TAG, "syncItems: " + e);
            }
            downloadHelper.editShoppingListItem(
                    itemToSync.getId(),
                    body,
                    response -> {
                        ShoppingListItem serverItem = serverItemHashMap.get(itemToSync.getId());
                        if(serverItem != null) serverItem.setDone(itemToSync.getDone());
                    },
                    error -> showMessage(getString(R.string.msg_failed_to_sync))
            );
        }
    }

    @Override
    public void storedDataSuccessfully(ArrayList<ShoppingListItem> shoppingListItems) {
        isDataStored = true;
        this.shoppingListItems = shoppingListItems;
        onQueueEmpty();
    }

    @Override
    public void onItemRowClicked(int position) {
        toggleDoneStatus(position);
    }

    public void toggleDoneStatus(int position) {
        ShoppingListItem shoppingListItem = (ShoppingListItem) groupedListItems.get(position);
        toggleDoneStatus(shoppingListItem, position, null);
    }

    public void toggleDoneStatus(
            @NonNull ShoppingListItem shoppingListItem,
            int position,
            ArrayList<GroupedListItem> removedItems
    ) {
        if(shoppingListItem.getDoneSynced() == -1) {
            shoppingListItem.setDoneSynced(shoppingListItem.getDone());
        }

        shoppingListItem.setDone(shoppingListItem.getDone() == 0 ? 1 : 0);  // toggle state

        if(showOffline) {
            updateDoneStatus(shoppingListItem, position, removedItems);
            return;
        }

        downloadHelper.getTimeDbChanged(date -> {
            boolean syncNeeded = this.lastSynced == null || this.lastSynced.before(date);
            JSONObject body = new JSONObject();
            try {
                body.put("done", shoppingListItem.getDone());
            } catch (JSONException e) {
                if(debug) Log.e(TAG, "toggleDoneStatus: " + e);
            }
            downloadHelper.editShoppingListItem(
                    shoppingListItem.getId(),
                    body,
                    response -> {
                        updateDoneStatus(shoppingListItem, position, removedItems);
                        if(syncNeeded) {
                            downloadShoppingListItems();
                        } else {
                            downloadHelper.getTimeDbChanged(
                                    date1 -> lastSynced = date1,
                                    () -> lastSynced = Calendar.getInstance().getTime()
                            );
                            lastSynced = Calendar.getInstance().getTime();
                        }
                    },
                    error -> {
                        updateDoneStatus(shoppingListItem, position, removedItems);
                        loadOfflineData();
                    },
                    false
            );
        }, () -> {
            updateDoneStatus(shoppingListItem, position, removedItems);
            loadOfflineData();
        });
    }

    private void updateDoneStatus(
            ShoppingListItem shoppingListItem,
            int position,
            ArrayList<GroupedListItem> removedItemsOld
    ) {
        new Thread(() -> database.shoppingListItemDao().update(shoppingListItem)).start();
        if(shoppingListItem.getDone() == 1) {
            shoppingListItemsSelected.remove(shoppingListItem);
            ArrayList<GroupedListItem> removedItemsNew = removeItemFromList(position);
            Snackbar snackbar = Snackbar.make(
                    binding.recycler,
                    R.string.msg_item_marked_as_done,
                    Snackbar.LENGTH_LONG
            );
            snackbar.setAction(
                    R.string.action_undo,
                    v -> toggleDoneStatus(shoppingListItem, position, removedItemsNew)
            );
            snackbar.setActionTextColor(ContextCompat.getColor(this, R.color.secondary));
            snackbar.show();
        } else if(removedItemsOld != null && !removedItemsOld.isEmpty()) {
            if(debug) Log.i(TAG, "updateDoneStatus: " + removedItemsOld);
            if(removedItemsOld.get(0).getType() == GroupedListItem.TYPE_HEADER) {
                int headerPosition = position - 1;
                groupedListItems.addAll(headerPosition, removedItemsOld);
                shoppingItemAdapter.notifyItemRangeInserted(headerPosition, removedItemsOld.size());
            } else if(position <= groupedListItems.size()) {
                if(debug) Log.i(TAG, "updateDoneStatus: " + position);
                groupedListItems.addAll(position, removedItemsOld);
                shoppingItemAdapter.notifyItemRangeInserted(position, removedItemsOld.size());
            } else {
                groupedListItems.addAll(removedItemsOld);
                shoppingItemAdapter.notifyItemRangeInserted(position, removedItemsOld.size());
            }
        }
    }

    private ArrayList<GroupedListItem> removeItemFromList(int position) {
        return ShoppingListHelper.removeItemFromList(
                shoppingItemAdapter,
                groupedListItems,
                position
        );
    }

    private void initTimerTask() {
        if(timerTask != null) timerTask.cancel();
        timerTask = new TimerTask() {
            @Override
            public void run() {
                if(!downloadHelper.isQueueEmpty()) return;
                downloadHelper.getTimeDbChanged(
                        date -> {
                            if(!netUtil.isOnline()) {
                                loadOfflineData();
                            } else if(lastSynced == null || lastSynced.before(date)) {
                                downloadShoppingListItems();
                            } else {
                                if(debug) Log.i(TAG, "run: skip sync of list items");
                            }
                        },
                        () -> downloadShoppingListItems()
                );
            }
        };
    }

    private void showShoppingListsBottomSheet() {
        Bundle bundle = new Bundle();
        bundle.putParcelableArrayList(Constants.ARGUMENT.SHOPPING_LISTS, shoppingLists);
        bundle.putInt(Constants.ARGUMENT.SELECTED_ID, selectedShoppingListId);
        bundle.putBoolean(Constants.ARGUMENT.SHOW_OFFLINE, true);
        showBottomSheet(new ShoppingListsBottomSheetDialogFragment(), bundle);
    }

    public void selectShoppingList(int shoppingListId) {
        if(shoppingListId == selectedShoppingListId) return;
        ShoppingList shoppingList = getShoppingList(shoppingListId);
        if(shoppingList == null) return;
        selectedShoppingListId = shoppingListId;
        sharedPrefs.edit().putInt(Constants.PREF.SHOPPING_LIST_LAST_ID, shoppingListId).apply();
        changeAppBarTitle(shoppingList);
        if(showOffline) {
            new LoadOfflineDataShoppingListHelper(
                    AppDatabase.getAppDatabase(getApplicationContext()),
                    this
            ).execute();
        } else {
            onQueueEmpty();
        }
    }

    private void changeAppBarTitle(ShoppingList shoppingList) {
        ShoppingListHelper.changeAppBarTitle(binding.textTitle, binding.buttonLists, shoppingList);
    }

    private void changeAppBarTitle() {
        ShoppingList shoppingList = getShoppingList(selectedShoppingListId);
        changeAppBarTitle(shoppingList);
    }

    private ShoppingList getShoppingList(int shoppingListId) {
        if(shoppingListHashMap.isEmpty()) {
            for(ShoppingList s : shoppingLists) shoppingListHashMap.put(s.getId(), s);
        }
        return shoppingListHashMap.get(shoppingListId);
    }

    public void showBottomSheet(@NonNull BottomSheetDialogFragment bottomSheet, Bundle bundle) {
        String tag = bottomSheet.toString();
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if (fragment == null || !fragment.isVisible()) {
            if(bundle != null) bottomSheet.setArguments(bundle);
            getSupportFragmentManager().beginTransaction().add(bottomSheet, tag).commit();
            if(debug) Log.i(TAG, "showBottomSheet: " + tag);
        } else if(debug) Log.e(TAG, "showBottomSheet: sheet already visible");
    }

    private void showMessage(String msg) {
        Snackbar.make(binding.recycler, msg, Snackbar.LENGTH_SHORT).show();
    }
}