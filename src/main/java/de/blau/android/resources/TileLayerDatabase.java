package de.blau.android.resources;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import de.blau.android.osm.BoundingBox;
import de.blau.android.resources.TileLayerServer.Provider;
import de.blau.android.resources.TileLayerServer.Provider.CoverageArea;
import de.blau.android.util.collections.MultiHashMap;

public class TileLayerDatabase extends SQLiteOpenHelper {
    private static final String DEBUG_TAG        = "TileLayerDatabase";
    public static final String DATABASE_NAME    = "tilelayers";
    private static final int    DATABASE_VERSION = 1;

    public static final String SOURCE_ELI    = "eli";    // editor-layer-index
    public static final String SOURCE_CUSTOM = "custom"; // user add tile layer

    private static final String SOURCES_TABLE = "sources";
    static final String         NAME_FIELD    = "name";
    private static final String UPDATED_FIELD = "updated";

    private static final String LAYERS_TABLE        = "layers";
    private static final String ID_FIELD            = "id";
    private static final String TYPE_FIELD          = "server_type";
    private static final String SOURCE_FIELD        = "source";
    private static final String TILE_URL_FIELD      = "url";
    private static final String TOU_URI_FIELD       = "tou_url";
    private static final String ATTRIBUTION_FIELD   = "attribution";
    private static final String OVERLAY_FIELD       = "overlay";
    private static final String DEFAULTLAYER_FIELD  = "default_layer";
    private static final String ZOOM_MIN_FIELD      = "zoom_min";
    private static final String ZOOM_MAX_FIELD      = "zoom_max";
    private static final String OVER_ZOOM_MAX_FIELD = "over_zoom_max";
    private static final String TILE_WIDTH_FIELD    = "tile_width";
    private static final String TILE_HEIGHT_FIELD   = "tile_height";
    private static final String PROJ_FIELD          = "proj";
    private static final String PREFERENCE_FIELD    = "preference";
    private static final String START_DATE_FIELD    = "start_date";
    private static final String END_DATE_FIELD      = "end_date";
    private static final String LOGO_URL_FIELD      = "logo_url";
    private static final String LOGO_FIELD          = "logo";

    private static final String COVERAGES_TABLE = "coverages";
    private static final String LEFT_FIELD      = "left";
    private static final String BOTTOM_FIELD    = "bottom";
    private static final String RIGHT_FIELD     = "right";
    private static final String TOP_FIELD       = "top";

    static final String QUERY_LAYER_BY_ROWID = "SELECT * FROM layers WHERE rowid=?";

    /**
     * Create a new instance of TileLayerDatabase creating the underlying DB is necessary
     * 
     * @param context Android Context
     */
    public TileLayerDatabase(final Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE sources (name TEXT NOT NULL PRIMARY KEY, updated INTEGER)");
            addSource(db, SOURCE_ELI);
            addSource(db, SOURCE_CUSTOM);

            db.execSQL(
                    "CREATE TABLE layers (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, server_type TEXT NOT NULL, source TEXT NOT NULL, url TEXT NOT NULL,"
                            + " tou_url TEXT, attribution TEXT, overlay INTEGER NOT NULL DEFAULT 0,"
                            + " default_layer INTEGER NOT NULL DEFAULT 0, zoom_min INTEGER NOT NULL DEFAULT 0, zoom_max INTEGER NOT NULL DEFAULT 18,"
                            + " over_zoom_max INTEGER NOT NULL DEFAULT 4, tile_width INTEGER NOT NULL DEFAULT 256, tile_height INTEGER NOT NULL DEFAULT 256,"
                            + " proj TEXT DEFAULT NULL, preference INTEGER NOT NULL DEFAULT 0, start_date INTEGER DEFAULT NULL, end_date INTEGER DEFAULT NULL,"
                            + " logo_url TEXT DEFAULT NULL, logo BLOB DEFAULT NULL, FOREIGN KEY(source) REFERENCES sources(name) ON DELETE CASCADE)");
            db.execSQL("CREATE INDEX layers_overlay_idx ON layers(overlay)");
            db.execSQL("CREATE INDEX layers_source_idx ON layers(source)");
            db.execSQL("CREATE TABLE coverages (id TEXT NOT NULL, zoom_min INTEGER NOT NULL DEFAULT 0, zoom_max INTEGER NOT NULL DEFAULT 18,"
                    + " left INTEGER DEFAULT NULL, bottom INTEGER DEFAULT NULL, right INTEGER DEFAULT NULL, top INTEGER DEFAULT NULL,"
                    + " FOREIGN KEY(id) REFERENCES layers(id) ON DELETE CASCADE)");
            db.execSQL("CREATE INDEX coverages_idx ON coverages(id)");
        } catch (SQLException e) {
            Log.w(DEBUG_TAG, "Problem creating database", e);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(DEBUG_TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!db.isReadOnly()) {
            // Enable foreign key constraints
            db.execSQL("PRAGMA foreign_keys=ON;");
        }
    }

    /**
     * Add an entry to the source table
     * 
     * @param db writable ruleset database
     * @param source name of the source to add
     */
    public static void addSource(@NonNull SQLiteDatabase db, @NonNull String source) {
        ContentValues values = new ContentValues();
        values.put(NAME_FIELD, source);
        db.insert(SOURCES_TABLE, null, values);
    }

    /**
     * Get the updated value for a source
     * 
     * @param db writable ruleset database
     * @param source name of the source
     * @return a milliseconds since th epoch value or 0 if not set
     */
    public static long getSourceUpdate(@NonNull SQLiteDatabase db, @NonNull String source) {
        Cursor dbresult = db.query(SOURCES_TABLE, null, NAME_FIELD + "='" + source + "'", null, null, null, null);
        if (dbresult.getCount() >= 1) {
            boolean haveEntry = dbresult.moveToFirst();
            if (haveEntry) {
                return dbresult.getLong(dbresult.getColumnIndex(UPDATED_FIELD));
            }
        }
        return 0;
    }

    /**
     * Delete a specific source which will delete all layers from that source
     * 
     * @param db writable database
     * @param source name of the entry
     * @param updated time in milliseconds when we updated
     */
    public static void updateSource(final SQLiteDatabase db, @NonNull String source, long updated) {
        Log.d(DEBUG_TAG, "Updating " + source + " " + updated);
        ContentValues values = new ContentValues();
        values.put(UPDATED_FIELD, updated);
        db.update(SOURCES_TABLE, values, "name='" + source + "'", null);
    }

    /**
     * Delete a specific source which will delete all layers from that source
     * 
     * @param db writable database
     * @param source name of the entry
     */
    public static void deleteSource(final SQLiteDatabase db, @NonNull String source) {
        db.delete(SOURCES_TABLE, NAME_FIELD + "=?", new String[] { source });
    }

    /**
     * Add a layer, will add coverage areas to the coverage table
     * 
     * @param db writable database
     * @param source source the layer comes from
     * @param layer a TileLayerServer object
     */
    public static void addLayer(@NonNull SQLiteDatabase db, @NonNull String source, @NonNull TileLayerServer layer) {
        ContentValues values = getContentValuesForLayer(source, layer);
        try {
            db.insertOrThrow(LAYERS_TABLE, null, values);
            // Log.d(DEBUG_TAG, "Added layer from " + source + ": " + layer);
            addCoverageFromLayer(db, layer);
        } catch (SQLiteConstraintException e) {
            // even when in a transaction only this insert will get rolled back
            Log.e(DEBUG_TAG, "Constraint exception " + layer.getId() + " " + e.getMessage());
        }
    }

    private static void addCoverageFromLayer(SQLiteDatabase db, TileLayerServer layer) {
        // insert coverage areas
        List<CoverageArea> coverages = layer.getCoverage();
        if (coverages != null) {
            for (CoverageArea ca : coverages) {
                addCoverage(db, layer.getId(), ca);
            }
        }
    }

    /**
     * Get an ContentValues object suitable for insertion or an update of a layer
     * 
     * @param source the source of the layer, use null if this is an update
     * @param layer TileLayerServer object holding the valuse
     * @return a ContentValues object
     */
    private static ContentValues getContentValuesForLayer(@Nullable String source, @NonNull TileLayerServer layer) {
        ContentValues values = new ContentValues();
        values.put(ID_FIELD, layer.getId());
        values.put(NAME_FIELD, layer.getName());
        values.put(TYPE_FIELD, layer.getType());
        if (source != null) {
            values.put(SOURCE_FIELD, source);
        }
        values.put(TILE_URL_FIELD, layer.getOriginalTileUrl());
        values.put(TOU_URI_FIELD, layer.getTouUri());
        values.put(ATTRIBUTION_FIELD, layer.getAttribution());
        values.put(OVERLAY_FIELD, layer.isOverlay() ? 1 : 0);
        values.put(DEFAULTLAYER_FIELD, layer.isDefaultLayer() ? 1 : 0);
        if (!TileLayerServer.TYPE_BING.equals(layer.getType())) { // bing layer gets these values dynamically
            values.put(ZOOM_MIN_FIELD, layer.getMinZoomLevel());
            values.put(ZOOM_MAX_FIELD, layer.getMaxZoomLevel());
            values.put(TILE_WIDTH_FIELD, layer.getTileWidth());
            values.put(TILE_HEIGHT_FIELD, layer.getTileHeight());
        }
        values.put(OVER_ZOOM_MAX_FIELD, layer.getMaxOverZoom());
        values.put(PROJ_FIELD, layer.getProj());
        values.put(PREFERENCE_FIELD, layer.getPreference());
        values.put(START_DATE_FIELD, layer.getStartDate());
        values.put(END_DATE_FIELD, layer.getEndDate());
        values.put(LOGO_URL_FIELD, layer.getLogoUrl());
        Bitmap logo = layer.getLogo();
        if (logo != null) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            logo.compress(Bitmap.CompressFormat.PNG, 100, stream);
            byte[] byteArray = stream.toByteArray();
            values.put(LOGO_FIELD, byteArray);
        }
        return values;
    }

    /**
     * Retrieve a single layer identified by its id
     * 
     * @param context Androic Context
     * @param db readable SWLiteDatabase
     * @param id the layer id
     * @return a TileLayerServer instance of null if none could be found
     */
    public static TileLayerServer getLayer(@NonNull Context context, @NonNull SQLiteDatabase db, @NonNull String id) {
        TileLayerServer layer = null;
        Cursor dbresult = db.query(COVERAGES_TABLE, null, ID_FIELD + "='" + id + "'", null, null, null, null);
        Provider provider = getProviderFromCursor(dbresult);

        dbresult = db.query(LAYERS_TABLE, null, ID_FIELD + "='" + id + "'", null, null, null, null);
        if (dbresult.getCount() >= 1) {
            boolean haveEntry = dbresult.moveToFirst();
            if (haveEntry) {
                layer = getLayerFromCursor(context, provider, dbresult);
            }
        }
        dbresult.close();
        return layer;
    }

    /**
     * Retrieve a single layer identified by its mysql rowid
     * 
     * @param context Androic Context
     * @param db readable SWLiteDatabase
     * @param rowId the mysql rowid
     * @return a TileLayerServer instance of null if none could be found
     */
    public static TileLayerServer getLayerWithRowId(@NonNull Context context, @NonNull SQLiteDatabase db, @NonNull int rowId) {
        TileLayerServer layer = null;
        Cursor dbresult = db.rawQuery(
                "SELECT left,bottom,right,top,coverages.zoom_min as zoom_min,coverages.zoom_max as zoom_max FROM layers,coverages WHERE layers.rowid=? AND layers.id=coverages.id",
                new String[] { Integer.toString(rowId) });
        Provider provider = getProviderFromCursor(dbresult);

        dbresult = db.rawQuery(QUERY_LAYER_BY_ROWID, new String[] { Integer.toString(rowId) });
        if (dbresult.getCount() >= 1) {
            boolean haveEntry = dbresult.moveToFirst();
            if (haveEntry) {
                layer = getLayerFromCursor(context, provider, dbresult);
            }
        }
        dbresult.close();
        return layer;
    }

    /**
     * Create Provider object containing CoverageAreas from a Cursor
     * 
     * @param cursor the Cursor
     * @return a Provider instance
     */
    private static Provider getProviderFromCursor(Cursor cursor) {
        Provider provider = new Provider();
        if (cursor.getCount() >= 1) {
            Log.d(DEBUG_TAG, "Got 1 or more coverage areas");
            boolean haveEntry = cursor.moveToFirst();
            while (haveEntry) {
                CoverageArea ca = getCoverageFromCursor(cursor);
                provider.addCoverageArea(ca);
                haveEntry = cursor.moveToNext();
            }
        }
        cursor.close();
        return provider;
    }

    /**
     * Update an existing layer in the database
     * 
     * @param db a writable SQLiteDatabase
     * @param layer the layer to write to the database
     */
    public static void updateLayer(SQLiteDatabase db, TileLayerServer layer) {
        String id = layer.getId();
        Log.d(DEBUG_TAG, "Updating layer " + id);
        deleteCoverage(db, id);
        ContentValues values = getContentValuesForLayer(null, layer);
        db.update(LAYERS_TABLE, values, "id=?", new String[] { id });
        addCoverageFromLayer(db, layer);
    }

    /**
     * Delete the layer with the SQLite rowid
     * 
     * @param db a writable SQLiteDatabase
     * @param rowId the rowId
     */
    public static void deleteLayerWithRowId(SQLiteDatabase db, int rowId) {
        db.delete(LAYERS_TABLE, "layers.rowid=?", new String[] { Integer.toString(rowId) });
    }

    /**
     * Create a CoverageArea from a Cursor
     * 
     * @param cursor the Cursor
     * @return a CoverageArea instance
     */
    private static CoverageArea getCoverageFromCursor(Cursor cursor) {
        int left = cursor.getInt(cursor.getColumnIndex(LEFT_FIELD));
        int bottom = cursor.getInt(cursor.getColumnIndex(BOTTOM_FIELD));
        int right = cursor.getInt(cursor.getColumnIndex(RIGHT_FIELD));
        int top = cursor.getInt(cursor.getColumnIndex(TOP_FIELD));
        BoundingBox box = new BoundingBox(left, bottom, right, top);
        int zoomMin = cursor.getInt(cursor.getColumnIndex(ZOOM_MIN_FIELD));
        int zoomMax = cursor.getInt(cursor.getColumnIndex(ZOOM_MAX_FIELD));
        CoverageArea ca = new CoverageArea(zoomMin, zoomMax, box);
        return ca;
    }

    /**
     * Get a Cursor for all layers
     * 
     * @param db a readable SQLiteDatabase
     * @return a Cursor pointing to all imagery layers
     */
    static Cursor getAllLayers(@NonNull SQLiteDatabase db) {
        return db.rawQuery("SELECT layers.rowid as _id, name FROM layers", null);
    }

    /**
     * Get a Cursor for all user defined layers
     * 
     * @param db a readable SQLiteDatabase
     * @return a Cursor pointing to all custom imagery layers
     */
    static Cursor getAllCustomLayers(@NonNull SQLiteDatabase db) {
        return db.rawQuery("SELECT layers.rowid as _id, name FROM layers WHERE source=?", new String[] { SOURCE_CUSTOM });
    }

    /**
     * Get all layers of a specific type
     * 
     * @param context Android Context
     * @param db a readable SQLiteDatabase
     * @param overlay if true only overlay layers will be returned
     * @return a Map containing the selected layers
     */
    public static Map<String, TileLayerServer> getAllLayers(@NonNull Context context, @NonNull SQLiteDatabase db, boolean overlay) {
        Map<String, TileLayerServer> layers = new HashMap<>();
        MultiHashMap<String, CoverageArea> coverages = new MultiHashMap<>();

        Cursor dbresult = db.rawQuery(
                "SELECT coverages.id as id,left,bottom,right,top,coverages.zoom_min as zoom_min,coverages.zoom_max as zoom_max FROM layers,coverages WHERE overlay=?",
                new String[] { overlay ? "1" : "0" });
        if (dbresult.getCount() >= 1) {
            boolean haveEntry = dbresult.moveToFirst();
            while (haveEntry) {
                String id = dbresult.getString(dbresult.getColumnIndex(ID_FIELD));
                CoverageArea ca = getCoverageFromCursor(dbresult);
                coverages.add(id, ca);
                haveEntry = dbresult.moveToNext();
            }
        }
        dbresult.close();

        dbresult = db.query(LAYERS_TABLE, null, OVERLAY_FIELD + "=" + (overlay ? 1 : 0), null, null, null, null);
        if (dbresult.getCount() >= 1) {
            boolean haveEntry = dbresult.moveToFirst();
            while (haveEntry) {
                String id = dbresult.getString(dbresult.getColumnIndex(ID_FIELD));
                Provider provider = new Provider();
                for (CoverageArea ca : coverages.get(id)) {
                    provider.addCoverageArea(ca);
                }
                TileLayerServer layer = getLayerFromCursor(context, provider, dbresult);
                layers.put(id, layer);
                haveEntry = dbresult.moveToNext();
            }
        }
        dbresult.close();

        return layers;
    }

    /**
     * Create a TileLayerServer from a database entry
     * 
     * @param context Android Context
     * @param provider Provider object holding coverage and attribution
     * @param cursor the Cursor
     * @return a TileLayerServer instance
     */
    private static TileLayerServer getLayerFromCursor(Context context, Provider provider, Cursor cursor) {
        String id = cursor.getString(cursor.getColumnIndex(ID_FIELD));
        String name = cursor.getString(cursor.getColumnIndex(NAME_FIELD));
        String type = cursor.getString(cursor.getColumnIndex(TYPE_FIELD));
        String tileUrl = cursor.getString(cursor.getColumnIndex(TILE_URL_FIELD));
        String touUri = cursor.getString(cursor.getColumnIndex(TOU_URI_FIELD));
        String attribution = cursor.getString(cursor.getColumnIndex(ATTRIBUTION_FIELD));
        provider.setAttribution(attribution);
        boolean overlay = cursor.getInt(cursor.getColumnIndex(OVERLAY_FIELD)) == 1;
        boolean defaultLayer = cursor.getInt(cursor.getColumnIndex(DEFAULTLAYER_FIELD)) == 1;
        int zoomLevelMin = cursor.getInt(cursor.getColumnIndex(ZOOM_MIN_FIELD));
        int zoomLevelMax = cursor.getInt(cursor.getColumnIndex(ZOOM_MAX_FIELD));
        int tileWidth = cursor.getInt(cursor.getColumnIndex(TILE_WIDTH_FIELD));
        int tileHeight = cursor.getInt(cursor.getColumnIndex(TILE_HEIGHT_FIELD));
        String proj = cursor.getString(cursor.getColumnIndex(PROJ_FIELD));
        int preference = cursor.getInt(cursor.getColumnIndex(PREFERENCE_FIELD));
        long startDate = cursor.getLong(cursor.getColumnIndex(START_DATE_FIELD));
        long endDate = cursor.getLong(cursor.getColumnIndex(END_DATE_FIELD));
        int maxOverZoom = cursor.getInt(cursor.getColumnIndex(OVER_ZOOM_MAX_FIELD));
        String logoUrl = cursor.getString(cursor.getColumnIndex(LOGO_URL_FIELD));
        byte[] logoBytes = cursor.getBlob(cursor.getColumnIndex(LOGO_FIELD));

        return new TileLayerServer(context, id, name, tileUrl, type, overlay, defaultLayer, provider, touUri, null, logoUrl, logoBytes, zoomLevelMin,
                zoomLevelMax, maxOverZoom, tileWidth, tileHeight, proj, preference, startDate, endDate, true);
    }

    /**
     * Add a CoverageArea to the database
     * 
     * @param db a writable database
     * @param layerId the id of the layer we are associated with
     * @param coverage the CoverageArea object
     */
    private static void addCoverage(@NonNull SQLiteDatabase db, @NonNull String layerId, @NonNull CoverageArea coverage) {
        ContentValues values = new ContentValues();
        values.put(ID_FIELD, layerId);
        values.put(ZOOM_MIN_FIELD, coverage.getMinZoomLevel());
        values.put(ZOOM_MAX_FIELD, coverage.getMaxZoomLevel());
        BoundingBox box = coverage.getBoundingBox();
        if (box != null) {
            values.put(LEFT_FIELD, box.getLeft());
            values.put(BOTTOM_FIELD, box.getBottom());
            values.put(RIGHT_FIELD, box.getRight());
            values.put(TOP_FIELD, box.getTop());
            db.insert(COVERAGES_TABLE, null, values);
            // Log.d(DEBUG_TAG, "Added box for " + layerId + ": " + box.toApiString());
        }
    }

    /**
     * Delete all coverage areas for a specific layer id
     * 
     * @param db a writable database
     * @param id the id for which we want to delete the coverage
     */
    public static void deleteCoverage(SQLiteDatabase db, String id) {
        db.delete(COVERAGES_TABLE, "id=?", new String[] { id });
    }
}