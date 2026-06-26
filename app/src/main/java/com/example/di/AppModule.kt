package com.example.di

import android.content.Context
import android.hardware.usb.UsbManager
import com.example.core.logging.AndroidLogger
import com.example.core.logging.Logger
import com.example.core.usb.UsbConnectionManager
import com.example.core.usb.UsbHostManager
import com.example.core.usb.UsbLogger
import com.example.core.usb.UsbMonitor
import com.example.core.usb.UsbPermissionManager
import com.example.core.usb.UsbViewModel
import com.example.core.usb.HpViewModel
import com.example.core.usb.transport.UsbPacketLogger
import com.example.core.usb.transport.UsbTransport
import com.example.core.usb.transport.UsbCommunicationViewModel
import com.example.domain.repository.UsbDeviceRepository
import com.example.domain.repository.UsbRepository
import com.example.domain.repository.UsbCommunicationRepository
import com.example.domain.repository.UsbDeviceCommunicationRepository
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Main Koin dependency injection module.
 * Provides all core application dependencies.
 */
val appModule = module {
    // Logging Systems
    single<Logger> { AndroidLogger() }
    single { UsbLogger(get()) }
    single { UsbPacketLogger(get()) }

    // Android USB System Services
    single { androidContext().getSystemService(Context.USB_SERVICE) as UsbManager }

    // Core USB Managers & Observers
    single { UsbPermissionManager(androidContext(), get(), get()) }
    single { UsbConnectionManager(get(), get()) }
    single { UsbMonitor(androidContext(), get()) }
    single { UsbHostManager(get(), get(), get(), get()) }
    single { UsbTransport(get()) }

    // Repositories
    single<UsbRepository> { UsbDeviceRepository(get(), get()) }
    single<UsbCommunicationRepository> { UsbDeviceCommunicationRepository(get(), get()) }

    // ViewModels
    viewModel { UsbViewModel(get()) }
    viewModel { HpViewModel(get(), get()) }
    viewModel { UsbCommunicationViewModel(get()) }
}
