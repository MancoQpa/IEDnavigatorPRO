package com.iednavigator.native_lib;

import com.sun.jna.*;
import com.sun.jna.ptr.*;

/**
 * JNA Interface for libiec61850 native library
 * Provides access to GOOSE and Sampled Values functionality
 *
 * Developer: Emilio Medina
 */
public interface LibIec61850 extends Library {

    // Load the native library
    LibIec61850 INSTANCE = Native.load("iec61850", LibIec61850.class);

    // ===================== GOOSE Receiver Functions =====================

    /**
     * Create a new GOOSE receiver instance
     */
    Pointer GooseReceiver_create();

    /**
     * Set the interface for the GOOSE receiver
     */
    void GooseReceiver_setInterfaceId(Pointer receiver, String interfaceId);

    /**
     * Get the interface ID used by the GOOSE receiver
     */
    String GooseReceiver_getInterfaceId(Pointer receiver);

    /**
     * Add a subscriber to this receiver instance
     */
    void GooseReceiver_addSubscriber(Pointer receiver, Pointer subscriber);

    /**
     * Remove a subscriber from this receiver instance
     */
    void GooseReceiver_removeSubscriber(Pointer receiver, Pointer subscriber);

    /**
     * Start the GOOSE receiver in a separate thread
     */
    void GooseReceiver_start(Pointer receiver);

    /**
     * Stop the GOOSE receiver
     */
    void GooseReceiver_stop(Pointer receiver);

    /**
     * Check if GOOSE receiver is running
     */
    boolean GooseReceiver_isRunning(Pointer receiver);

    /**
     * Free all resources of the GooseReceiver
     */
    void GooseReceiver_destroy(Pointer receiver);

    // ===================== GOOSE Subscriber Functions =====================

    /**
     * Create a new GOOSE subscriber
     * @param goCbRef GoCB reference in MMS notation
     * @param dataSetValues MmsValue array for data set (can be null)
     */
    Pointer GooseSubscriber_create(String goCbRef, Pointer dataSetValues);

    /**
     * Set the callback listener for GOOSE messages
     */
    void GooseSubscriber_setListener(Pointer subscriber, GooseListener listener, Pointer parameter);

    /**
     * Configure the subscriber to listen to any GOOSE message (observer mode)
     */
    void GooseSubscriber_setObserver(Pointer subscriber);

    /**
     * Set destination MAC address filter
     */
    void GooseSubscriber_setDstMac(Pointer subscriber, byte[] dstMac);

    /**
     * Set APPID filter
     */
    void GooseSubscriber_setAppId(Pointer subscriber, short appId);

    /**
     * Check if subscriber state is valid
     */
    boolean GooseSubscriber_isValid(Pointer subscriber);

    /**
     * Destroy the GOOSE subscriber
     */
    void GooseSubscriber_destroy(Pointer subscriber);

    /**
     * Get the GoId value
     */
    String GooseSubscriber_getGoId(Pointer subscriber);

    /**
     * Get the GoCB reference
     */
    String GooseSubscriber_getGoCbRef(Pointer subscriber);

    /**
     * Get the DataSet value
     */
    String GooseSubscriber_getDataSet(Pointer subscriber);

    /**
     * Get APPID value
     */
    int GooseSubscriber_getAppId(Pointer subscriber);

    /**
     * Get source MAC address
     */
    void GooseSubscriber_getSrcMac(Pointer subscriber, byte[] buffer);

    /**
     * Get destination MAC address
     */
    void GooseSubscriber_getDstMac(Pointer subscriber, byte[] buffer);

    /**
     * Get state number (stNum)
     */
    int GooseSubscriber_getStNum(Pointer subscriber);

    /**
     * Get sequence number (sqNum)
     */
    int GooseSubscriber_getSqNum(Pointer subscriber);

    /**
     * Check if test flag is set
     */
    boolean GooseSubscriber_isTest(Pointer subscriber);

    /**
     * Get configuration revision (confRev)
     */
    int GooseSubscriber_getConfRev(Pointer subscriber);

    /**
     * Check needs commission flag
     */
    boolean GooseSubscriber_needsCommission(Pointer subscriber);

    /**
     * Get TimeAllowedToLive value
     */
    int GooseSubscriber_getTimeAllowedToLive(Pointer subscriber);

    /**
     * Get timestamp of last message
     */
    long GooseSubscriber_getTimestamp(Pointer subscriber);

    /**
     * Get data set values
     */
    Pointer GooseSubscriber_getDataSetValues(Pointer subscriber);

    /**
     * Check if VLAN is set
     */
    boolean GooseSubscriber_isVlanSet(Pointer subscriber);

    /**
     * Get VLAN ID
     */
    short GooseSubscriber_getVlanId(Pointer subscriber);

    /**
     * Get VLAN priority
     */
    byte GooseSubscriber_getVlanPrio(Pointer subscriber);

    // ===================== SV Receiver Functions =====================

    /**
     * Create a new SV receiver
     */
    Pointer SVReceiver_create();

    /**
     * Set interface ID for SV receiver
     */
    void SVReceiver_setInterfaceId(Pointer receiver, String interfaceId);

    /**
     * Disable destination address check
     */
    void SVReceiver_disableDestAddrCheck(Pointer receiver);

    /**
     * Enable destination address check
     */
    void SVReceiver_enableDestAddrCheck(Pointer receiver);

    /**
     * Add a subscriber to the SV receiver
     */
    void SVReceiver_addSubscriber(Pointer receiver, Pointer subscriber);

    /**
     * Remove a subscriber from the SV receiver
     */
    void SVReceiver_removeSubscriber(Pointer receiver, Pointer subscriber);

    /**
     * Start the SV receiver
     */
    void SVReceiver_start(Pointer receiver);

    /**
     * Stop the SV receiver
     */
    void SVReceiver_stop(Pointer receiver);

    /**
     * Check if SV receiver is running
     */
    boolean SVReceiver_isRunning(Pointer receiver);

    /**
     * Destroy the SV receiver
     */
    void SVReceiver_destroy(Pointer receiver);

    // ===================== SV Subscriber Functions =====================

    /**
     * Create a new SV subscriber
     * @param ethAddr optional destination address (null to not specify)
     * @param appID the APP-ID to identify matching SV messages
     */
    Pointer SVSubscriber_create(byte[] ethAddr, short appID);

    /**
     * Set callback handler for SV messages
     */
    void SVSubscriber_setListener(Pointer subscriber, SVUpdateListener listener, Pointer parameter);

    /**
     * Destroy SV subscriber
     */
    void SVSubscriber_destroy(Pointer subscriber);

    // ===================== SV ASDU Functions =====================

    /**
     * Get sample count
     */
    short SVSubscriber_ASDU_getSmpCnt(Pointer asdu);

    /**
     * Get SV ID
     */
    String SVSubscriber_ASDU_getSvId(Pointer asdu);

    /**
     * Get DataSet reference
     */
    String SVSubscriber_ASDU_getDatSet(Pointer asdu);

    /**
     * Get configuration revision
     */
    int SVSubscriber_ASDU_getConfRev(Pointer asdu);

    /**
     * Get sample mode
     */
    byte SVSubscriber_ASDU_getSmpMod(Pointer asdu);

    /**
     * Get sample rate
     */
    short SVSubscriber_ASDU_getSmpRate(Pointer asdu);

    /**
     * Check if DataSet is present
     */
    boolean SVSubscriber_ASDU_hasDatSet(Pointer asdu);

    /**
     * Check if RefrTm is present
     */
    boolean SVSubscriber_ASDU_hasRefrTm(Pointer asdu);

    /**
     * Check if SmpMod is present
     */
    boolean SVSubscriber_ASDU_hasSmpMod(Pointer asdu);

    /**
     * Check if SmpRate is present
     */
    boolean SVSubscriber_ASDU_hasSmpRate(Pointer asdu);

    /**
     * Get reference time as milliseconds
     */
    long SVSubscriber_ASDU_getRefrTmAsMs(Pointer asdu);

    /**
     * Get INT8 data value
     */
    byte SVSubscriber_ASDU_getINT8(Pointer asdu, int index);

    /**
     * Get INT16 data value
     */
    short SVSubscriber_ASDU_getINT16(Pointer asdu, int index);

    /**
     * Get INT32 data value
     */
    int SVSubscriber_ASDU_getINT32(Pointer asdu, int index);

    /**
     * Get INT64 data value
     */
    long SVSubscriber_ASDU_getINT64(Pointer asdu, int index);

    /**
     * Get INT8U data value
     */
    byte SVSubscriber_ASDU_getINT8U(Pointer asdu, int index);

    /**
     * Get INT16U data value
     */
    short SVSubscriber_ASDU_getINT16U(Pointer asdu, int index);

    /**
     * Get INT32U data value
     */
    int SVSubscriber_ASDU_getINT32U(Pointer asdu, int index);

    /**
     * Get INT64U data value
     */
    long SVSubscriber_ASDU_getINT64U(Pointer asdu, int index);

    /**
     * Get FLOAT32 data value
     */
    float SVSubscriber_ASDU_getFLOAT32(Pointer asdu, int index);

    /**
     * Get FLOAT64 data value
     */
    double SVSubscriber_ASDU_getFLOAT64(Pointer asdu, int index);

    /**
     * Get data size of ASDU
     */
    int SVSubscriber_ASDU_getDataSize(Pointer asdu);

    /**
     * Get sample synchronization
     */
    byte SVSubscriber_ASDU_getSmpSynch(Pointer asdu);

    // ===================== MmsValue Functions =====================

    /**
     * Get the type of an MmsValue
     */
    int MmsValue_getType(Pointer value);

    /**
     * Get element count for array/structure
     */
    int MmsValue_getArraySize(Pointer value);

    /**
     * Get array element
     */
    Pointer MmsValue_getElement(Pointer value, int index);

    /**
     * Get boolean value
     */
    boolean MmsValue_getBoolean(Pointer value);

    /**
     * Get integer value
     */
    int MmsValue_toInt32(Pointer value);

    /**
     * Get unsigned integer value
     */
    int MmsValue_toUint32(Pointer value);

    /**
     * Get float value
     */
    float MmsValue_toFloat(Pointer value);

    /**
     * Get double value
     */
    double MmsValue_toDouble(Pointer value);

    /**
     * Get bit string value as integer
     */
    int MmsValue_getBitStringAsInteger(Pointer value);

    /**
     * Get string value
     */
    String MmsValue_toString(Pointer value);

    // ===================== Callback Interfaces =====================

    /**
     * GOOSE message listener callback
     */
    interface GooseListener extends Callback {
        void invoke(Pointer subscriber, Pointer parameter);
    }

    /**
     * SV update listener callback
     */
    interface SVUpdateListener extends Callback {
        void invoke(Pointer subscriber, Pointer parameter, Pointer asdu);
    }

    // ===================== MmsValue Type Constants =====================

    int MMS_ARRAY = 0;
    int MMS_STRUCTURE = 1;
    int MMS_BOOLEAN = 2;
    int MMS_BIT_STRING = 3;
    int MMS_INTEGER = 4;
    int MMS_UNSIGNED = 5;
    int MMS_FLOAT = 6;
    int MMS_OCTET_STRING = 7;
    int MMS_VISIBLE_STRING = 8;
    int MMS_GENERALIZED_TIME = 9;
    int MMS_BINARY_TIME = 10;
    int MMS_BCD = 11;
    int MMS_OBJ_ID = 12;
    int MMS_STRING = 13;
    int MMS_UTC_TIME = 14;
    int MMS_DATA_ACCESS_ERROR = 15;
}
