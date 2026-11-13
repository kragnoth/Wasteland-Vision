package com.kragnoth.wastelandvision.vision;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Map;

/**
 * A parsed vision folder: config/wastelandvision/visions/<id>/vision.json (+ assets/).
 * Rules and atmosphere stay as JSON here and are compiled on the client when the vision is used,
 * because rules reference tags, which only exist once a world is loaded.
 */
public record Vision(String id, String displayName, String description, Path folder, JsonObject json,
                     Map<String, StandIn> standins) {
}
