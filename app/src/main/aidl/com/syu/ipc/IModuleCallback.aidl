// IModuleCallback.aidl
package com.syu.ipc;

interface IModuleCallback {
    oneway void update(int type, in int[] intData, in float[] floatData, in String[] stringData);
}
