package com.example.parentphoto.settings

import org.json.JSONArray
import org.json.JSONObject

/** 送り先一覧のJSON変換(ds 8.1 recipients_json)。順序は保持する。 */
object RecipientJson {
    fun encode(list: List<Recipient>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("email", it.email))
        }
        return arr.toString()
    }

    /** 壊れたJSONは空リスト、不正な要素は読み飛ばす。 */
    fun decode(json: String): List<Recipient> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty()) null else Recipient(id, o.optString("name"), o.optString("email"))
        }
    } catch (e: org.json.JSONException) {
        emptyList()
    }
}
