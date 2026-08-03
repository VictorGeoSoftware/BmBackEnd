package com.bm.backend.services

/**
 * Raised by [UserDataService] when a login attempt presents a device
 * identifier that differs from the one already bound to the account
 * ("one phone per account").
 *
 * The transport layer maps this to HTTP 403 with a "contact administration"
 * message. An administrator must reset the binding (see
 * [UserDataService.resetDeviceBinding]) before the new device can log in.
 */
class DeviceMismatchException(uid: String) :
    RuntimeException("Account $uid is already bound to a different device")
