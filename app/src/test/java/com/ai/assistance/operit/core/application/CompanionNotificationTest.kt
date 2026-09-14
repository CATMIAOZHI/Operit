package com.ai.assistance.operit.core.application

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import org.junit.Test
import org.mockito.kotlin.*

class CompanionNotificationTest {
    @Test fun closingWindowsKeepsTheTaskNotificationUntilTheLastServiceStops() {
        val manager = mock<NotificationManager>()
        val ai = mock<Service>()
        val pet = mock<Service>()
        val chat = mock<Service>()
        listOf(ai, pet, chat).forEach {
            whenever(it.getSystemService(NotificationManager::class.java)).thenReturn(manager)
        }
        val task = mock<Notification>()
        val ignoredWindowContent = mock<Notification>()
        try {
            CompanionNotification.startForeground(ai, 1, task, 0)
            CompanionNotification.startForeground(pet, 1027, ignoredWindowContent, 0)
            CompanionNotification.startForeground(chat, 1001, ignoredWindowContent, 0)
            verify(pet).startForeground(1, task)
            verify(chat).startForeground(1, task)

            CompanionNotification.release(pet)
            CompanionNotification.release(chat)
            verify(pet).stopForeground(Service.STOP_FOREGROUND_DETACH)
            verify(chat).stopForeground(Service.STOP_FOREGROUND_DETACH)
            verify(manager, never()).cancel(1)
            CompanionNotification.release(ai)
            verify(manager).cancel(1)

            clearInvocations(manager)
            CompanionNotification.update(ai, task)
            CompanionNotification.release(ai)
            verifyNoInteractions(manager)
        } finally {
            CompanionNotification.release(pet)
            CompanionNotification.release(chat)
            CompanionNotification.release(ai)
        }
    }
}
