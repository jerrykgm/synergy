/*
 * Deskflow -- mouse and keyboard sharing utility
 * Copyright (C) 2024 Symless Ltd.
 *
 * This package is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * found in the file LICENSE that should have accompanied this file.
 *
 * This package is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#pragma once

#include <QObject>
#include <QTimer>
#include <QUdpSocket>
#include <QHostAddress>
#include <QMap>

// UDP port used for local network discovery broadcasts
static constexpr quint16 kDiscoveryPort = 24802;

// Broadcast message identifier
static const QByteArray kDiscoveryMagic = "SYNERGY_DISCOVER_V1";

// How often the server broadcasts its presence (ms)
static constexpr int kBroadcastIntervalMs = 3000;

// How long before a discovered peer is considered gone (ms)
static constexpr int kPeerTimeoutMs = 10000;

struct DiscoveredServer {
  QString hostname;
  QString ip;
  quint16 port;
};

class NetworkDiscovery : public QObject
{
  Q_OBJECT

public:
  explicit NetworkDiscovery(QObject *parent = nullptr);
  ~NetworkDiscovery() override;

  // Start broadcasting as a server on the local network
  void startBroadcasting(const QString &hostname, quint16 synergyPort);

  // Start listening for server broadcasts (client mode)
  void startListening();

  // Stop all discovery activity
  void stop();

  // Get currently discovered servers
  QList<DiscoveredServer> discoveredServers() const;

signals:
  // Emitted when a new server is found or an existing one is updated
  void serverDiscovered(const DiscoveredServer &server);

  // Emitted when a server disappears from the network
  void serverLost(const QString &ip);

private slots:
  void onBroadcastTimer();
  void onCleanupTimer();
  void onDatagramReceived();

private:
  void sendBroadcast();
  void parseDatagram(const QByteArray &data, const QHostAddress &sender, quint16 senderPort);

  QUdpSocket *m_pBroadcastSocket = nullptr;
  QUdpSocket *m_pListenSocket = nullptr;
  QTimer *m_pBroadcastTimer = nullptr;
  QTimer *m_pCleanupTimer = nullptr;

  QString m_hostname;
  quint16 m_synergyPort = 24800;

  // ip -> (DiscoveredServer, last-seen timestamp ms)
  QMap<QString, QPair<DiscoveredServer, qint64>> m_peers;
};
