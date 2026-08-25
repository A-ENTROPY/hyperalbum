package com.smartvision.gallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import com.smartvision.gallery.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Places365 ResNet50 int8 TFLite 场景分类器 (365 类).
 *
 * 输入: 224×224 float32 [1,3,224,224], ImageNet 归一化 ((val/255)-mean)/std
 * 输出: [1,365] float32 softmax
 *
 * 365 类 → 14 场景映射: [sceneFromPlaces365] 按 label 路径 + 关键字做语义路由.
 * 返回 null 表示 Places365 无法映射 → 上游 fallback 到 ImageNet/颜色 heuristic.
 */
class Places365Classifier(context: Context) {

    data class SceneResult(
        val sceneIdx: Int,
        val confidence: Float,
        val label: String
    )

    private val interpreter by lazy { AiModelHub.get(context).places365() }
    private val labels: Array<String> by lazy { loadLabels(context) }
    private val diagCount = java.util.concurrent.atomic.AtomicLong(0L)

    fun isReady(): Boolean = interpreter != null

    /**
     * 单帧分类: resize → float32 input → argmax → sceneFromPlaces365 → SceneResult.
     * 返回 null = Places365 无法映射到 14 场景 (e.g. 模型输出非场景对象如 food).
     */
    fun classify(bitmap: Bitmap): SceneResult? {
        val itp = interpreter ?: return null
        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            for (p in pixels) {
                input.putFloat((((p shr 16) and 0xFF) / 255f - MEAN_R) / STD_R)
                input.putFloat((((p shr 8) and 0xFF) / 255f - MEAN_G) / STD_G)
                input.putFloat(((p and 0xFF) / 255f - MEAN_B) / STD_B)
            }
            input.rewind()
            val output = Array(1) { FloatArray(365) }
            synchronized(itp) { itp.run(input, output) }
            val probs = output[0]
            val top1Idx = probs.indices.maxByOrNull { probs[it] } ?: return null
            val top1Conf = probs[top1Idx]
            val label = if (top1Idx in labels.indices) labels[top1Idx] else return null
            val scene = sceneFromPlaces365(label) ?: return null

            val dc = diagCount.incrementAndGet()
            if (dc % 30L == 0L) {
                AppLog.i(TAG, "top1 #$dc idx=$top1Idx label=$label conf=${"%.3f".format(top1Conf)} → scene=$scene")
            }
            SceneResult(scene, top1Conf, label)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Places365 inference failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "Places365Classifier"
        private const val INPUT_SIZE = 224
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f

        const val SCENE_PERSON = 0
        const val SCENE_PORTRAIT = 1
        const val SCENE_NIGHT = 2
        const val SCENE_SUNSET = 3
        const val SCENE_SNOW = 4
        const val SCENE_WATER = 5
        const val SCENE_FOOD = 6
        const val SCENE_INDOOR = 7
        const val SCENE_PLANT = 8
        const val SCENE_BUILDING = 9
        const val SCENE_DOCUMENT = 10
        const val SCENE_SKY = 11
        const val SCENE_BABY = 12
        const val SCENE_ANIMAL = 13

        private fun loadLabels(context: Context): Array<String> {
            return try {
                context.assets.open("places365_labels.txt").bufferedReader().use { reader ->
                    val arr = Array(365) { "" }
                    for (line in reader.readLines()) {
                        val trimmed = line.trim()
                        val spaceIdx = trimmed.lastIndexOf(' ')
                        if (spaceIdx > 0) {
                            val name = trimmed.substring(0, spaceIdx).trim('/')
                            val num = trimmed.substring(spaceIdx + 1).trim().toIntOrNull()
                            if (num != null && num in 0..364) arr[num] = name
                        }
                    }
                    arr
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "Failed to load labels", t)
                Array(365) { "" }
            }
        }

        /**
         * 365 Places365 label → 14 场景映射.
         * 语义分组: 按 label 路径的关键段匹配, 未命中返回 null.
         *
         * 优先级顺序很关键: 越具体的场景(ANIMAL/FOOD/BABY/DOCUMENT/PLANT)必须
         * 在通用 INDOOR catchall 之前, 否则 pet_shop/restaurnt/nursery
         * 等会被强制归到 INDOOR (导致 71.8% 室内假阳性的主因之一).
         */
        fun sceneFromPlaces365(label: String): Int? {
            val path = label.lowercase()
            val base = path.substringAfterLast('/')
            val top = path.substringBefore('/')

            // ─── WATER ───
            if (base in setOf("ocean", "coast", "beach", "lagoon", "lake", "river",
                    "waterfall", "wave", "pond", "creek", "harbor", "pier",
                    "swimming_hole", "swamp", "marsh", "berth", "boat_deck",
                    "boathouse", "fishpond", "fountain", "hot_spring",
                    "watering_hole", "dam", "lock_chamber", "water_tower",
                    "water_park", "raft", "canal", "floe", "ice_shelf", "iceberg",
                    "swimming_pool", "ocean_deep")
            ) return SCENE_WATER
            if (base == "moat" && path.contains("water")) return SCENE_WATER
            if (top == "underwater") return SCENE_WATER
            if (path.contains("canal/")) return SCENE_WATER
            if (base == "natural" && (path.contains("/canal") || path.contains("/lake"))) return SCENE_WATER

            // ─── SNOW / ICE ───
            if (base in setOf("snowfield", "glacier", "tundra", "mountain_snowy",
                    "ice_skating_rink", "igloo")
            ) return SCENE_SNOW
            if (path.contains("ski_")) return SCENE_SNOW

            // ─── SKY / FLYING ───
            if (base in setOf("sky", "airfield", "runway", "heliport")) return SCENE_SKY

            // ─── PLANT / VEGETATION (before INDOOR/BUILDING) ───
            if (base in setOf("forest", "bamboo_forest", "rainforest",
                    "forest_path", "forest_road", "forest/broadleaf",
                    "botanical_garden", "formal_garden", "japanese_garden",
                    "topiary_garden", "zen_garden", "roof_garden",
                    "orchard", "tree_farm", "tree_house",
                    "vineyard", "wheat_field", "corn_field", "hayfield",
                    "rice_paddy", "field/cultivated", "field/wild",
                    "field_road", "pasture", "meadow", "savanna",
                    "vegetable_garden", "flower_garden", "herb_garden",
                    "park", "picnic_area", "lawn",
                    "hedge", "shrubbery", "plantation",
                    "prairie", "steppe", "savannah",
                    "grove", "woodland", "garden")
            ) return SCENE_PLANT

            // ─── SUNSET / SCENIC (warm-toned outdoor landscapes) ───
            if (base in setOf("sunset", "sunrise", "dusk", "golden_hour",
                    "canyon", "desert/sand", "desert/vegetation",
                    "desert_road", "butte", "mesa", "badlands",
                    "volcano", "cliff", "crevasse", "valley",
                    "mountain", "mountain_path", "hill", "hillside",
                    "rock_arch", "grotto", "cave", "cavern",
                    "field", "farm", "farmland", "hayfield",
                    "coastline", "seashore", "shore", "seaside",
                    "lakeside", "reservoir",
                    "island", "islet", "peninsula",
                    "plains", "plateau", "highland")
            ) return SCENE_SUNSET

            // ─── NIGHT / DARK (specific dark indoor places) ───
            if (base in setOf("darkroom", "observatory")) return SCENE_NIGHT

            // ─── ANIMAL (specific — BEFORE INDOOR catchall) ───
            if (base in setOf("kennel/outdoor", "pet_shop", "veterinarians_office",
                    "aquarium", "stable", "corral", "bird_house", "bird_feeder",
                    "hunting_lodge/outdoor", "henhouse", "chicken_coop")
            ) return SCENE_ANIMAL

            // ─── FOOD (specific — BEFORE INDOOR catchall) ───
            if (base in setOf("bakery/shop", "butchers_shop", "candy_store",
                    "delicatessen", "ice_cream_parlor", "fastfood_restaurant",
                    "restaurant", "restaurant_kitchen", "sushi_bar",
                    "pizzeria", "cafeteria", "food_court",
                    "coffee_shop", "bar", "beer_garden", "beer_hall",
                    "pub/indoor", "wet_bar", "wine_cellar", "wine_cave",
                    "winery", "brewery", "distillery")
            ) return SCENE_FOOD

            // ─── BABY (specific — BEFORE INDOOR catchall) ───
            if (base in setOf("nursery", "childs_room", "kindergarden_classroom",
                    "playroom", "ball_pit", "toy_shop", "toy_store")
            ) return SCENE_BABY

            // ─── DOCUMENT (specific — BEFORE INDOOR catchall) ───
            if (base in setOf("archive", "library/indoor", "library",
                    "bookstore")
            ) return SCENE_DOCUMENT

            // ─── BUILDING (outdoor man-made structures) ───
            if (base in setOf("building_facade", "skyscraper", "house", "castle",
                    "palace", "tower", "pagoda", "lighthouse", "windmill",
                    "bridge", "viaduct", "steel_arch_bridge", "suspension_bridge",
                    "church", "mosque", "synagogue", "temple", "monastery",
                    "shrine", "cathedral", "mausoleum",
                    "wind_farm", "oilrig",
                    "oast_house", "manufactured_home", "mobile_home", "shed",
                    "greenhouse", "greenhouse/outdoor",
                    "stadium/baseball", "stadium/football", "stadium/soccer",
                    "soccer_field", "football_field", "baseball_field",
                    "basketball_court/outdoor", "volleyball_court/outdoor",
                    "tennis_court", "golf_course", "racecourse", "raceway",
                    "athletic_field/outdoor",
                    "amusement_park", "fairway", "putting_green",
                    "parking_lot", "parking_garage/outdoor",
                    "gas_station", "highway", "street", "crosswalk",
                    "alley", "downtown", "plaza", "market/outdoor",
                    "shopfront", "bazaar/outdoor", "general_store/outdoor",
                    "hotel/outdoor", "motel", "inn/outdoor", "resort",
                    "campground", "campsite", "trailer_park",
                    "construction_site", "industrial_area", "junkyard", "landfill",
                    "excavation", "trench", "mine", "quarry",
                    "ranch",
                    "fire_station", "police_station",
                    "post_office", "courthouse", "town_hall", "capitol",
                    "embassy", "fortress",
                    "monument", "memorial", "obelisk", "triumphal_arch",
                    "ruin", "archaelogical_excavation", "kasbah", "medina",
                    "ticket_booth", "booth/outdoor",
                    "bus_station/outdoor", "train_station/outdoor",
                    "subway_station/outdoor", "ferry_terminal",
                    "dock", "loading_dock", "wharf",
                    "boardwalk", "promenade", "esplanade",
                    "fire_escape", "doorway/outdoor", "porch", "patio",
                    "balcony/exterior", "deck", "veranda",
                    "gazebo/exterior", "pavilion",
                    "arch", "columnade", "portico",
                    "airport_terminal", "bus_station/outdoor",
                    "landing_deck", "hangar", "hangar/outdoor",
                    "auto_showroom/outdoor", "factory/outdoor")
            ) return SCENE_BUILDING
            if (base == "outdoor" && (path.contains("/apartment") || path.contains("/beach_house")
                    || path.contains("/cabin") || path.contains("/cottage")
                    || path.contains("/house") || path.contains("/chalet"))) return SCENE_BUILDING
            if (base == "exterior" && path.contains("balcony")) return SCENE_BUILDING
            if (base == "outdoor" && path.contains("restaurant_patio")) return SCENE_BUILDING
            if (base == "auto_showroom" || base == "auto_factory") return SCENE_BUILDING
            if (base == "assembly_line" || base == "factory") return SCENE_BUILDING
            if (path.contains("parking_garage")) return SCENE_BUILDING

            // ─── INDOOR (unambiguous interior spaces only) ───
            if (base in setOf(
                    // 居住空间
                    "living_room", "bedroom", "dining_room", "kitchen",
                    "bathroom", "bedchamber", "hotel_room", "dorm_room",
                    "home_office", "home_theater",
                    // 通道/入口
                    "corridor", "hallway", "staircase", "elevator",
                    "elevator_lobby", "lobby", "reception", "entrance_hall",
                    "mezzanine", "atrium/public", "alcove",
                    "alcove/bed", "alcove/dining", "alcove/living", "alcove/study",
                    // 办公/会议
                    "office", "conference_room", "classroom", "boardroom",
                    "war_room", "situation_room", "mail_room", "copy_room",
                    "break_room", "study", "reading_room",
                    // 商业/娱乐 (明显室内场所)
                    "shopping_mall/indoor", "department_store", "supermarket",
                    "market/indoor", "bazaar/indoor",
                    "clothing_store", "shoe_shop", "jewelry_shop",
                    "fabric_store", "drugstore", "pharmacy",
                    "gift_shop", "general_store/indoor",
                    "furniture_store", "antique_shop",
                    "movie_theater/indoor", "theater", "orchestra_pit",
                    "stage/indoor", "casino", "amusement_arcade", "bowling_alley",
                    "arcade",
                    // 餐饮 (保留部分, 因 cafe/bar/bier 几乎都是室内照)
                    "pizzeria", "cafeteria", "food_court",
                    "restaurant", "restaurant_kitchen", "fastfood_restaurant",
                    "ice_cream_parlor", "coffee_shop", "sushi_bar",
                    "delicatessen", "wet_bar", "pub/indoor",
                    "beer_hall", "bar", "banquet_hall",
                    "nightclub", "discotheque",
                    // 专用房间
                    "auditorium", "throne_room", "ballroom",
                    "hospital_room", "hospital", "nursing_home",
                    "dentists_office",
                    "operating_room", "laboratory", "chemistry_lab",
                    "physics_laboratory", "biology_laboratory",
                    "art_studio", "artists_loft", "music_studio",
                    "television_studio", "recording_studio",
                    "clean_room",
                    // 仓储/工具
                    "storage_room", "utility_room", "pantry", "closet",
                    "attic", "basement", "garage/indoor",
                    "workshop", "repair_shop",
                    "laundry_room", "boiler_room", "furnace_room",
                    "electrical_room", "telecom_room", "server_room",
                    "computer_room",
                    "laundromat", "beauty_salon", "barbershop", "salon", "spa",
                    // 文化/教育
                    "art_gallery", "gallery", "exhibition_hall",
                    "museum/indoor", "science_museum", "natural_history_museum",
                    "bookstore", "library", "library/indoor",
                    "church/indoor", "temple/asia", "catacomb", "burial_chamber",
                    "chapel", "meditation_space", "prayer_room",
                    "legislative_chamber", "courtroom",
                    // 其他
                    "shower", "sauna", "jacuzzi/indoor", "solarium",
                    "dressing_room", "waiting_room", "locker_room",
                    "game_room", "recreation_room",
                    "prison", "jail_cell", "interrogation_room",
                    "massage_room", "mud_room", "crawl_space", "vault", "sewer",
                    // 车辆/特殊舱室 (按 indoor 处理)
                    "cockpit", "engine_room", "control_room",
                    "train_interior", "bus_interior", "airplane_cabin",
                    "cabin/outdoor",
                    "greenhouse/indoor", "booth/indoor", "bow_window/indoor",
                    "phone_booth",
                    "youth_hostel", "dormitory", "bunk_room"
            )) return SCENE_INDOOR
            if (base == "indoor" && (path.contains("/basketball") || path.contains("/ice_skating")
                    || path.contains("/gymnasium"))) return SCENE_INDOOR
            if (base == "indoor" && path.contains("/market")) return SCENE_INDOOR
            if (base == "indoor" && path.contains("/booth")) return SCENE_INDOOR
            if (base == "indoor" && path.contains("/bow_window")) return SCENE_INDOOR
            if (base == "indoor" && path.contains("/gym")) return SCENE_INDOOR

            // no match
            return null
        }
    }
}