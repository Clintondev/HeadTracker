package com.r2d2.headtracker

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

class UsbSerialHelper(context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    /** Abre a primeira porta USB-Serial encontrada */
    fun open() {
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull() ?: return
        val connection = usbManager.openDevice(driver.device) ?: return
        serialPort = driver.ports.first().apply {
            open(connection)
            setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        }
        ioManager = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {}
            override fun onRunError(e: Exception) {}
        })
        Executors.newSingleThreadExecutor().submit(ioManager!!)
    }


    /** Grava o array de bytes na porta */
    fun write(data: ByteArray) {
        serialPort?.write(data, 1000)
    }

    /** Fecha tudo */
    fun close() {
        ioManager?.stop()
        serialPort?.close()
        ioManager = null
        serialPort = null
    }
}
