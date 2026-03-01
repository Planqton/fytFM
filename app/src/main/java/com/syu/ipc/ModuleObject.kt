package com.syu.ipc

import android.os.Parcel
import android.os.Parcelable

/**
 * Data object returned from IRemoteModule.get()
 */
data class ModuleObject(
    var intData: IntArray? = null,
    var floatData: FloatArray? = null,
    var stringData: Array<String>? = null
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.createIntArray(),
        parcel.createFloatArray(),
        parcel.createStringArray()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeIntArray(intData)
        parcel.writeFloatArray(floatData)
        parcel.writeStringArray(stringData)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ModuleObject
        if (intData != null) {
            if (other.intData == null) return false
            if (!intData.contentEquals(other.intData)) return false
        } else if (other.intData != null) return false
        if (floatData != null) {
            if (other.floatData == null) return false
            if (!floatData.contentEquals(other.floatData)) return false
        } else if (other.floatData != null) return false
        if (stringData != null) {
            if (other.stringData == null) return false
            if (!stringData.contentEquals(other.stringData)) return false
        } else if (other.stringData != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = intData?.contentHashCode() ?: 0
        result = 31 * result + (floatData?.contentHashCode() ?: 0)
        result = 31 * result + (stringData?.contentHashCode() ?: 0)
        return result
    }

    companion object CREATOR : Parcelable.Creator<ModuleObject> {
        override fun createFromParcel(parcel: Parcel): ModuleObject = ModuleObject(parcel)
        override fun newArray(size: Int): Array<ModuleObject?> = arrayOfNulls(size)
    }
}
