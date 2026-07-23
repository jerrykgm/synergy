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

#include "NetworkDiscovery.h"

#include <QDataStream>
#include <QDateTime>
#include <QHostInfo>
#include <QNetworkInterface>

NetworkDiscovery::NetworkDiscovery(QObject *parent) : QObject(parent)
{
}

NetworkDiscovery::~NetworkDiscovery()
{
  stop();
}

void NetworkDiscovery::startBroadcasting(const QString &hostname, quint16 synergyPort)
{
  startBroadcastingNode(hostname, NodeRole::Server, synergyPort);
}

void NetworkDiscovery::startBroadcastingNode(const QString &hostname, NodeRole role, quint16 synergyPort)
{
  m_hostname = hostname;
  m_role = role;
  m_synergyPort = synergyPort;

  if (!m_pBroadcastSocket) {
    m_pBroadcastSocket = new QUdpSocket(this);
    m_pBroadcastSocket->bind(QHostAddress::AnyIPv4, 0, QUdpSocket::ShareAddress);
  }

  if (!m_pBroadcastTimer) {
    m_pBroadcastTimer = new QTimer(this);
    connect(m_pBroadcastTimer, &QTimer::timeout, this, &NetworkDiscovery::onBroadcastTimer);
    m_pBroadcastTimer->start(kBroadcastIntervalMs);
  }

  // Send first broadcast immediately
  sendBroadcast();
}

void NetworkDiscovery::startListening()
{
  if (!m_pListenSocket) {
    m_pListenSocket = new QUdpSocket(this);
    if (m_pListenSocket->bind(QHostAddress::AnyIPv4, kDiscoveryPort, QUdpSocket::ShareAddress | QUdpSocket::ReuseAddressHint)) {
      connect(m_pListenSocket, &QUdpSocket::readyRead, this, &NetworkDiscovery::onDatagramReceived);
    }
  }

  if (!m_pCleanupTimer) {
    m_pCleanupTimer = new QTimer(this);
    connect(m_pCleanupTimer, &QTimer::timeout, this, &NetworkDiscovery::onCleanupTimer);
    m_pCleanupTimer->start(kBroadcastIntervalMs);
  }
}

void NetworkDiscovery::stop()
{
  if (m_pBroadcastTimer) {
    m_pBroadcastTimer->stop();
    m_pBroadcastTimer->deleteLater();
    m_pBroadcastTimer = nullptr;
  }

  if (m_pCleanupTimer) {
    m_pCleanupTimer->stop();
    m_pCleanupTimer->deleteLater();
    m_pCleanupTimer = nullptr;
  }

  if (m_pBroadcastSocket) {
    m_pBroadcastSocket->close();
    m_pBroadcastSocket->deleteLater();
    m_pBroadcastSocket = nullptr;
  }

  if (m_pListenSocket) {
    m_pListenSocket->close();
    m_pListenSocket->deleteLater();
    m_pListenSocket = nullptr;
  }

  m_peers.clear();
}

QList<DiscoveredServer> NetworkDiscovery::discoveredServers() const
{
  QList<DiscoveredServer> result;
  for (const auto &entry : m_peers) {
    if (entry.first.role == NodeRole::Server) {
      result.append(entry.first);
    }
  }
  return result;
}

QList<DiscoveredNode> NetworkDiscovery::discoveredNodes() const
{
  QList<DiscoveredNode> result;
  for (const auto &entry : m_peers) {
    result.append(entry.first);
  }
  return result;
}

void NetworkDiscovery::onBroadcastTimer()
{
  sendBroadcast();
}

void NetworkDiscovery::onCleanupTimer()
{
  const qint64 now = QDateTime::currentMSecsSinceEpoch();
  QList<QString> toRemove;

  for (auto it = m_peers.begin(); it != m_peers.end(); ++it) {
    if (now - it.value().second > kPeerTimeoutMs) {
      toRemove.append(it.key());
    }
  }

  for (const QString &ip : toRemove) {
    m_peers.remove(ip);
    emit serverLost(ip);
    emit nodeLost(ip);
  }
}

void NetworkDiscovery::onDatagramReceived()
{
  while (m_pListenSocket && m_pListenSocket->hasPendingDatagrams()) {
    QByteArray data;
    QHostAddress sender;
    quint16 senderPort;
    data.resize(static_cast<int>(m_pListenSocket->pendingDatagramSize()));
    m_pListenSocket->readDatagram(data.data(), data.size(), &sender, &senderPort);
    parseDatagram(data, sender, senderPort);
  }
}

void NetworkDiscovery::sendBroadcast()
{
  if (!m_pBroadcastSocket) {
    return;
  }

  // Build packet V2: magic | hostname | role | port
  QByteArray packet;
  QDataStream stream(&packet, QIODevice::WriteOnly);
  stream.setVersion(QDataStream::Qt_6_0);
  stream << kDiscoveryMagicV2;
  stream << m_hostname;
  stream << static_cast<qint32>(m_role);
  stream << m_synergyPort;

  // Broadcast on all network interfaces
  const QList<QNetworkInterface> interfaces = QNetworkInterface::allInterfaces();
  for (const QNetworkInterface &iface : interfaces) {
    if (!(iface.flags() & QNetworkInterface::IsUp) ||
        (iface.flags() & QNetworkInterface::IsLoopBack)) {
      continue;
    }

    const QList<QNetworkAddressEntry> entries = iface.addressEntries();
    for (const QNetworkAddressEntry &entry : entries) {
      if (entry.ip().protocol() != QAbstractSocket::IPv4Protocol) {
        continue;
      }
      const QHostAddress broadcast = entry.broadcast();
      if (!broadcast.isNull()) {
        m_pBroadcastSocket->writeDatagram(packet, broadcast, kDiscoveryPort);
      } else {
        m_pBroadcastSocket->writeDatagram(packet, QHostAddress::Broadcast, kDiscoveryPort);
      }
    }
  }
}

void NetworkDiscovery::parseDatagram(const QByteArray &data, const QHostAddress &sender, quint16 /*senderPort*/)
{
  QDataStream stream(data);
  stream.setVersion(QDataStream::Qt_6_0);

  QByteArray magic;
  stream >> magic;

  QString hostname;
  quint16 port = 24800;
  NodeRole role = NodeRole::Server;

  if (magic == kDiscoveryMagicV2) {
    qint32 rawRole = 0;
    stream >> hostname;
    stream >> rawRole;
    stream >> port;
    role = static_cast<NodeRole>(rawRole);
  } else if (magic == kDiscoveryMagic) {
    stream >> hostname;
    stream >> port;
    role = NodeRole::Server;
  } else {
    return;
  }

  if (stream.status() != QDataStream::Ok) {
    return;
  }

  const QString ip = sender.toString();
  const qint64 now = QDateTime::currentMSecsSinceEpoch();

  DiscoveredNode node{hostname, ip, port, role};
  const bool isNew = !m_peers.contains(ip);
  m_peers[ip] = {node, now};

  if (isNew) {
    emit nodeDiscovered(node);
    if (role == NodeRole::Server) {
      emit serverDiscovered(node);
    }
  }
}
