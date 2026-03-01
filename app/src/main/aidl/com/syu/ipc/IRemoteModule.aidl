// IRemoteModule.aidl
package com.syu.ipc;

import com.syu.ipc.IModuleCallback;
import com.syu.ipc.ModuleObject;

interface IRemoteModule {
    oneway void call(int type, in int[] intData, in float[] floatData, in String[] stringData);
    ModuleObject get(int type, in int[] intData, in float[] floatData, in String[] stringData);
    oneway void register(IModuleCallback callback, int eventType, int flags);
    oneway void unregister(IModuleCallback callback, int id);
}
