/*
 * Synergy -- mouse and keyboard sharing utility
 * ShareMouse-Style Peer Input Hook
 */

#pragma once

#include <QObject>
#include <QTimer>
#include <QPoint>
#include <QCursor>

class PeerInputHook : public QObject
{
  Q_OBJECT

public:
  explicit PeerInputHook(QObject *parent = nullptr);
  ~PeerInputHook() override;

  void startMonitoring();
  void stopMonitoring();
  bool isMonitoring() const { return m_monitoring; }

signals:
  // Emitted when local physical mouse movement or key activity is detected
  void physicalUserActivityDetected();

private slots:
  void checkMouseActivity();

private:
  QTimer *m_pTimer = nullptr;
  QPoint m_lastMousePos;
  bool m_monitoring = false;
};
