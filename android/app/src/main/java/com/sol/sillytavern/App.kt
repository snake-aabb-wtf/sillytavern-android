package com.sol.sillytavern

import android.app.Application

/**
 * Application entry point.
 *
 * The embedded server is owned by [com.sol.sillytavern.server.ServerController], a
 * process-wide idempotent singleton started by MainActivity; this class needs no custom
 * initialization.
 */
class App : Application()
