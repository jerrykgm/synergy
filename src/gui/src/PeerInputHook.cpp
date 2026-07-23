/*
 * Synergy -- mouse and keyboard sharing utility
 * ShareMouse-Style Peer Input Hook
 */

#include "PeerInputHook.h"
#include <QDebug>

PeerInputHook::PeerInputHook(QObject *parent) : QObject(parent)
{
}

PeerInputHook::~PeerInputHook()
{
  stopMonitoring();
}

void PeerInputHook::startMonitoring()
{
  if (m_monitoring) {
    return;
  }

  m_lastMousePos = QCursor::pos();
  m_monitoring = true;

  if (!m_pTimer) {
    m_pTimer = new QTimer(this);
    connect(m_pTimer, &QTimer::timeout, this, &PeerInputHook::checkMouseActivity);
  }

  // Poll cursor position at 50ms interval (20Hz) for rapid local activity detection
  m_pTimer->start(50);
  qDebug("PeerInputHook: Started monitoring physical user activity");
}

void PeerInputHook::stopMonitoring()
{
  if (!m_monitoring) {
    return;
  }

  if (m_pTimer) {
    m_pTimer->stop();
    m_pTimer->deleteLater();
    m_pTimer = nullptr;
  }

  m_monitoring = false;
  qDebug("PeerInputHook: Stopped monitoring");
}

void PeerInputHook::checkMouseActivity()
{
  const QPoint currentPos = QCursor::pos();
  if (currentPos != m_lastMousePos) {
    m_lastMousePos = currentPos;
    emit physicalUserActivityDetected();
  }
}
