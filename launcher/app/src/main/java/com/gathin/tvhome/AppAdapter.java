package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;

/**
 * App tiles with an installed-app library and editable pinned dock.
 * Saved ordering stays within the dock and grid respectively; new apps append alphabetically.
 */
public class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);
        void onOrderMoved(int position);
        void onLibraryFocused();
        void onLibraryUp();
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component == null ? "" : component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    /** Never shown anywhere, not even in the app library (res/values/config.xml, library_hidden_apps). */
    private final Set<String> libraryHidden;
    private final List<Entry> items = new ArrayList<>();
    private final List<Entry> library = new ArrayList<>();
    private int moving = -1;
    private List<Entry> beforeMove;

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = new ArrayList<>(Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps)));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        this.libraryHidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.library_hidden_apps)));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
        try {
            JSONArray savedDock = new JSONArray(ctx.getSharedPreferences("home", 0).getString("dock_apps", "[]"));
            List<String> custom = new ArrayList<>();
            for (int i = 0; i < savedDock.length(); i++) {
                String pkg = savedDock.getString(i);
                if ((pkg.isEmpty() || !custom.contains(pkg)) && !pkg.equals(ctx.getPackageName())) custom.add(pkg);
            }
            if (custom.size() == dockSize) { favourites.clear(); favourites.addAll(custom); }
        } catch (Exception ignored) { }
    }

    void reload() {
        moving = -1;
        List<String> saved = new ArrayList<>();
        try {
            JSONArray order = new JSONArray(ctx.getSharedPreferences("home", 0).getString("app_order", "[]"));
            for (int i = 0; i < order.length(); i++) saved.add(order.getString(i));
        } catch (Exception ignored) { }
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        Set<String> tvPackages = new HashSet<>();
        for (ResolveInfo ri : found) if (ri.activityInfo != null) tvPackages.add(ri.activityInfo.packageName);
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        Set<String> seen = new HashSet<>();
        Set<String> removed = ctx.getSharedPreferences("home", 0).getStringSet("home_hidden", Collections.emptySet());
        Set<String> added = ctx.getSharedPreferences("home", 0).getStringSet("home_added", Collections.emptySet());
        library.clear();
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || libraryHidden.contains(pkg) || !seen.add(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            Entry entry = new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon);
            library.add(entry);
            if (removed.contains(pkg) || (hidden.contains(pkg) && !added.contains(pkg))
                    || (!tvPackages.contains(pkg) && !added.contains(pkg))) continue;
            fresh.add(entry);
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if ((fa >= 0) != (fb >= 0)) return fa >= 0 ? -1 : 1;
            int sa = saved.indexOf(a.component.flattenToString());
            int sb = saved.indexOf(b.component.flattenToString());
            if (sa >= 0 || sb >= 0) {
                if (sa < 0) return 1;
                if (sb < 0) return -1;
                return Integer.compare(sa, sb);
            }
            if (fa >= 0 && fb >= 0) return Integer.compare(fa, fb);
            return a.label.compareToIgnoreCase(b.label);
        });
        items.clear();
        for (int slot = 0; slot < dockSize; slot++) {
            Entry pinned = null;
            String pkg = slot < favourites.size() ? favourites.get(slot) : "";
            for (Entry entry : fresh) if (entry.pkg.equals(pkg)) { pinned = entry; break; }
            items.add(pinned == null ? emptySlot() : pinned);
            if (pinned != null) fresh.remove(pinned);
        }
        items.addAll(fresh);
        library.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 2) {
            View row = LayoutInflater.from(ctx).inflate(R.layout.row_app_library, parent, false);
            TextView button = row.findViewById(R.id.appLibrary);
            button.setOnClickListener(v -> openLibrary(v, -1));
            button.setOnFocusChangeListener((v, focused) -> { if (focused) listener.onLibraryFocused(); });
            button.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) listener.onLibraryUp();
                return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
            });
            return new RecyclerView.ViewHolder(row) { };
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener, this);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int adapterPosition) {
        if (getItemViewType(adapterPosition) == 2) return;
        Holder h = (Holder) holder;
        int position = toAppPosition(adapterPosition);
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setContentDescription(e.label + (moving == position ? ctx.getString(R.string.moving_description) : ""));
        h.card.setOnClickListener(v -> {
            if (moving >= 0) finishMove(false);
            else if (e.component == null) openLibrary(v, items.indexOf(e));
            else launch(e);
        });
        h.card.setOnLongClickListener(v -> {
            // One menu per press: the long-click timer and the long-press key repeat both land here.
            if (h.handledLongPress) return true;
            h.handledLongPress = true;
            if (moving >= 0) return true;
            if (e.component == null) { openLibrary(v, items.indexOf(e)); return true; }
            List<AppMenu.Item> actions = new ArrayList<>();
            actions.add(new AppMenu.Item(ctx.getString(R.string.move_app), () -> {
                moving = items.indexOf(e); beforeMove = new ArrayList<>(items);
                Toast.makeText(ctx, ctx.getString(R.string.move_instructions), Toast.LENGTH_LONG).show();
            }));
            actions.add(new AppMenu.Item(ctx.getString(R.string.app_info), () -> {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
                try { ctx.startActivity(i); } catch (ActivityNotFoundException ignored) { }
            }));
            boolean pinned = items.indexOf(e) < dockSize;
            actions.add(new AppMenu.Item(ctx.getString(R.string.in_dock), () -> chooseDockReplacement(e, v), pinned));
            actions.add(new AppMenu.Item(ctx.getString(R.string.remove_from_home), () -> removeFromHome(e)));
            AppMenu.show((Activity) ctx, v, null, actions);
            return true;
        });
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size() + 1;
    }

    int appCount() { return items.size(); }
    int toAdapterPosition(int app) { return app; }
    int toAppPosition(int adapter) { return adapter; }
    @Override public int getItemViewType(int position) {
        return position == getItemCount() - 1 ? 2 : 0;
    }

    private Entry emptySlot() {
        return new Entry(ctx.getString(R.string.add_app), null, null, ctx.getDrawable(android.R.drawable.ic_input_add));
    }

    private void removeFromHome(Entry entry) {
        int position = items.indexOf(entry);
        if (position < 0) return;
        Set<String> removed = new HashSet<>(ctx.getSharedPreferences("home", 0)
                .getStringSet("home_hidden", Collections.emptySet()));
        removed.add(entry.pkg);
        ctx.getSharedPreferences("home", 0).edit().putStringSet("home_hidden", removed).apply();
        if (position < dockSize) items.set(position, emptySlot()); else items.remove(position);
        saveOrder(); notifyDataSetChanged();
        listener.onOrderMoved(Math.min(position, items.size() - 1));
        Toast.makeText(ctx, ctx.getString(R.string.removed_from_home), Toast.LENGTH_SHORT).show();
    }

    private int positionOfPackage(String pkg) {
        for (int i = 0; i < items.size(); i++) if (!pkg.isEmpty() && items.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    private void openLibrary(View anchor, int dockSlot) {
        List<AppMenu.Item> choices = new ArrayList<>();
        for (Entry entry : library) {
            String label = entry.label + (positionOfPackage(entry.pkg) >= 0 ? ctx.getString(R.string.on_home_suffix) : "");
            choices.add(new AppMenu.Item(label, () -> {
                Set<String> removed = new HashSet<>(ctx.getSharedPreferences("home", 0).getStringSet("home_hidden", Collections.emptySet()));
                Set<String> added = new HashSet<>(ctx.getSharedPreferences("home", 0).getStringSet("home_added", Collections.emptySet()));
                removed.remove(entry.pkg); added.add(entry.pkg);
                ctx.getSharedPreferences("home", 0).edit().putStringSet("home_hidden", removed).putStringSet("home_added", added).apply();
                int existing = positionOfPackage(entry.pkg), target;
                if (dockSlot >= 0) {
                    Entry old = items.get(dockSlot);
                    if (existing >= 0) {
                        if (old.component == null && existing >= dockSize) { items.remove(existing); items.set(dockSlot, entry); }
                        else Collections.swap(items, dockSlot, existing);
                    } else {
                        items.set(dockSlot, entry);
                        if (old.component != null) items.add(old);
                    }
                    target = dockSlot;
                } else {
                    if (existing < 0) { items.add(entry); target = items.size() - 1; }
                    else target = existing;
                }
                saveOrder(); notifyDataSetChanged(); listener.onOrderMoved(target);
            }));
        }
        AppMenu.show((Activity) ctx, anchor, dockSlot >= 0 ? ctx.getString(R.string.choose_dock_app) : ctx.getString(R.string.installed_app_library), choices);
    }

    boolean cancelMove() {
        if (moving < 0) return false;
        finishMove(true); return true;
    }

    private void chooseDockReplacement(Entry chosen, View anchor) {
        int position = items.indexOf(chosen);
        boolean pinned = position < dockSize;
        if (pinned) { openLibrary(anchor, position); return; }
        List<AppMenu.Item> choices = new ArrayList<>();
        int start = pinned ? dockSize : 0;
        int end = pinned ? items.size() : Math.min(dockSize, items.size());
        for (int i = start; i < end; i++) {
            Entry replacement = items.get(i);
            choices.add(new AppMenu.Item(replacement.label, () -> {
                int a = items.indexOf(chosen), b = items.indexOf(replacement);
                if (a < 0 || b < 0) return;
                if (items.get(b).component == null) { items.remove(a); items.set(b, chosen); }
                else Collections.swap(items, a, b);
                saveOrder(); notifyDataSetChanged();
                listener.onOrderMoved(pinned ? a : b);
                Toast.makeText(ctx, ctx.getString(R.string.dock_updated), Toast.LENGTH_SHORT).show();
            }));
        }
        AppMenu.show((Activity) ctx, anchor, pinned ? ctx.getString(R.string.replace_app, chosen.label) : ctx.getString(R.string.replace_in_dock), choices);
    }

    private void saveOrder() {
        JSONArray order = new JSONArray(), dock = new JSONArray();
        favourites.clear();
        for (int i = 0; i < items.size(); i++) {
            Entry e = items.get(i); if (e.component != null) order.put(e.component.flattenToString());
            if (i < dockSize) { favourites.add(e.pkg); dock.put(e.pkg); }
        }
        ctx.getSharedPreferences("home", 0).edit().putString("app_order", order.toString())
                .putString("dock_apps", dock.toString()).apply();
    }

    private void finishMove(boolean cancel) {
        int focus = moving;
        if (cancel && beforeMove != null) {
            Entry chosen = items.get(moving);
            items.clear(); items.addAll(beforeMove); focus = items.indexOf(chosen);
            notifyDataSetChanged();
        } else {
            saveOrder();
        }
        moving = -1; beforeMove = null;
        listener.onOrderMoved(focus);
        Toast.makeText(ctx, cancel ? ctx.getString(R.string.move_cancelled) : ctx.getString(R.string.app_order_saved), Toast.LENGTH_SHORT).show();
    }

    private boolean moveKey(int key) {
        if (moving < 0) return false;
        int target = moving;
        int start = moving < dockSize ? 0 : dockSize;
        int end = moving < dockSize ? Math.min(dockSize, items.size()) : items.size();
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) target--;
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) target++;
        else if (key == KeyEvent.KEYCODE_DPAD_UP) target -= dockSize;
        else if (key == KeyEvent.KEYCODE_DPAD_DOWN) target += dockSize;
        else return false;
        if (target >= start && target < end) {
            int old = moving;
            Entry entry = items.remove(old); items.add(target, entry); moving = target;
            notifyItemMoved(toAdapterPosition(old), toAdapterPosition(target));
            listener.onOrderMoved(target);
        }
        return true;
    }

    @Override
    public long getItemId(int position) {
        // The library footer and empty dock slots have no component; give them ids no app hash can take.
        if (position >= items.size()) return Long.MAX_VALUE;
        String key = keyAt(position);
        return key != null ? key.hashCode() : (1L << 32) + position;
    }

    /** Stable key for the app at a position (its component), or null for an empty slot. */
    String keyAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        ComponentName c = items.get(position).component;
        return c != null ? c.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (key.equals(keyAt(i))) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        boolean handledLongPress;

        Holder(View v, TileListener listener, AppAdapter owner) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                boolean select = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
                if (select && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                    handledLongPress = false;
                if (select && event.getAction() == KeyEvent.ACTION_UP && handledLongPress) {
                    handledLongPress = false; view.setPressed(false); return true;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (select && event.isLongPress()) {
                    // The view's own long-click timer may already have opened the menu for this press.
                    view.cancelLongPress();
                    if (!handledLongPress) view.performLongClick();
                    handledLongPress = true;
                    return true;
                }
                if (owner.moveKey(keyCode)) return true;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(owner.toAppPosition(pos), keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().cancel();
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(owner.toAppPosition(pos), view);
                }
            });
        }
    }
}
