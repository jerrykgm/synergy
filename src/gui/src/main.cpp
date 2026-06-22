/*
 * Deskflow -- mouse and keyboard sharing utility
 * Copyright (C) 2012 Symless Ltd.
 * Copyright (C) 2008 Volker Lanz (vl@fidra.de)
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

#ifdef DESKFLOW_GUI_HOOK_HEADER
#include DESKFLOW_GUI_HOOK_HEADER
#endif

#include "DeskflowApplication.h"
#include "MainWindow.h"
#include "SetupWizard.h"
#include "common/constants.h"
#include "gui/Logger.h"
#include "gui/config/AppConfig.h"
#include "gui/config/Settings.h"
#include "gui/constants.h"
#include "gui/diagnostic.h"
#include "gui/dotenv.h"
#include "gui/messages.h"
#include "gui/string_utils.h"
#include "version.h"

#include <QApplication>
#include <QDebug>
#include <QGuiApplication>
#include <QMessageBox>
#include <QObject>
#include <QtCore>
#include <QtGlobal>
#include <QtGui>

#if defined(Q_OS_MAC)
#include <Carbon/Carbon.h>
#include <cstdlib>
#endif

using namespace deskflow::gui;

class QThreadImpl : public QThread
{
public:
  static void msleep(unsigned long msecs)
  {
    QThread::msleep(msecs);
  }
};

#if defined(Q_OS_MAC)
bool checkMacAssistiveDevices();
#endif

bool hasArg(const QString &arg, const QStringList &args)
{
  return std::ranges::any_of(args, [&arg](const QString &a) { return a == arg; });
}

int main(int argc, char *argv[])
{
  // Fixes Fedora bug where qDebug() messages aren't printed.
  // HACK: Also shows the debug messages in release builds.
  qputenv("QT_LOGGING_RULES", "*.debug=true;qt.*=false");

#if defined(Q_OS_MAC)
  /* Workaround for QTBUG-40332 - "High ping when QNetworkAccessManager is
   * instantiated" */
  ::setenv("QT_BEARER_POLL_TIMEOUT", "-1", 1);
#endif

  QCoreApplication::setApplicationName(kAppName);
  QCoreApplication::setOrganizationName(kAppName);

  // used as a prefix for settings paths, and must not be a url.
  QCoreApplication::setOrganizationDomain(kOrgDomain);

  DeskflowApplication app(argc, argv);

  // Global stylesheet for a modern, user-friendly UI
  app.setStyleSheet(R"(
    QMainWindow, QDialog, QWidget {
      background-color: #1e1e2e;
      color: #cdd6f4;
      font-family: "Inter", "Segoe UI", "San Francisco", "Helvetica Neue", Arial, sans-serif;
      font-size: 13px;
    }

    /* --- Radio Buttons --- */
    QRadioButton {
      color: #cdd6f4;
      font-size: 14px;
      font-weight: 600;
      spacing: 10px;
      padding: 4px 0px;
    }
    QRadioButton::indicator {
      width: 20px;
      height: 20px;
      border-radius: 10px;
      border: 2.5px solid #585b70;
      background-color: #313244;
    }
    QRadioButton::indicator:hover {
      border-color: #ff7c00;
      background-color: #3a3a52;
    }
    QRadioButton::indicator:checked {
      border: 2.5px solid #ff7c00;
      background-color: #ff7c00;
      image: url(none);
    }
    QRadioButton::indicator:checked:hover {
      border-color: #ff9a3c;
      background-color: #ff9a3c;
    }
    QRadioButton:disabled {
      color: #6c7086;
    }

    /* --- Group Boxes --- */
    QGroupBox {
      background-color: #24273a;
      border: 1.5px solid #363a4f;
      border-radius: 10px;
      margin-top: 18px;
      padding: 14px 12px 12px 12px;
      font-size: 13px;
      font-weight: 700;
      color: #a6adc8;
    }
    QGroupBox::title {
      subcontrol-origin: margin;
      subcontrol-position: top left;
      left: 14px;
      top: -1px;
      padding: 0 6px;
      color: #cdd6f4;
      font-size: 12px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 1px;
      background-color: #24273a;
    }

    /* --- Push Buttons --- */
    QPushButton {
      background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
        stop:0 #ff9a3c, stop:1 #ff7c00);
      color: #ffffff;
      border: none;
      border-radius: 7px;
      padding: 7px 18px;
      font-size: 13px;
      font-weight: 600;
      min-width: 80px;
    }
    QPushButton:hover {
      background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
        stop:0 #ffb347, stop:1 #ff9a3c);
    }
    QPushButton:pressed {
      background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
        stop:0 #e06b00, stop:1 #c95c00);
      padding-top: 8px;
      padding-bottom: 6px;
    }
    QPushButton:disabled {
      background: #363a4f;
      color: #6c7086;
    }

    /* --- Line Edits --- */
    QLineEdit {
      background-color: #313244;
      color: #cdd6f4;
      border: 1.5px solid #45475a;
      border-radius: 7px;
      padding: 6px 10px;
      font-size: 13px;
      selection-background-color: #ff7c00;
      selection-color: #ffffff;
    }
    QLineEdit:focus {
      border-color: #ff7c00;
      background-color: #363a52;
    }
    QLineEdit:disabled {
      background-color: #2a2a3c;
      color: #6c7086;
    }

    /* --- Labels --- */
    QLabel {
      color: #cdd6f4;
      background: transparent;
    }

    /* --- Log Output --- */
    QPlainTextEdit {
      background-color: #11111b;
      color: #a6e3a1;
      border: 1.5px solid #313244;
      border-radius: 7px;
      padding: 6px;
      font-family: "JetBrains Mono", "Fira Code", "Courier New", monospace;
      font-size: 12px;
      selection-background-color: #ff7c00;
    }

    /* --- Menu Bar --- */
    QMenuBar {
      background-color: #181825;
      color: #cdd6f4;
      border-bottom: 1px solid #313244;
      padding: 2px;
    }
    QMenuBar::item:selected {
      background-color: #313244;
      border-radius: 4px;
    }
    QMenu {
      background-color: #1e1e2e;
      color: #cdd6f4;
      border: 1px solid #363a4f;
      border-radius: 8px;
      padding: 4px;
    }
    QMenu::item:selected {
      background-color: #ff7c00;
      color: #ffffff;
      border-radius: 4px;
    }

    /* --- Scroll Bars --- */
    QScrollBar:vertical {
      background: #181825;
      width: 8px;
      border-radius: 4px;
    }
    QScrollBar::handle:vertical {
      background: #45475a;
      border-radius: 4px;
      min-height: 20px;
    }
    QScrollBar::handle:vertical:hover {
      background: #ff7c00;
    }
    QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
      height: 0px;
    }
    QScrollBar:horizontal {
      background: #181825;
      height: 8px;
      border-radius: 4px;
    }
    QScrollBar::handle:horizontal {
      background: #45475a;
      border-radius: 4px;
      min-width: 20px;
    }
    QScrollBar::handle:horizontal:hover {
      background: #ff7c00;
    }

    /* --- Combo Boxes --- */
    QComboBox {
      background-color: #313244;
      color: #cdd6f4;
      border: 1.5px solid #45475a;
      border-radius: 7px;
      padding: 6px 10px;
      font-size: 13px;
    }
    QComboBox:focus {
      border-color: #ff7c00;
    }
    QComboBox::drop-down {
      border: none;
      padding-right: 8px;
    }
    QComboBox QAbstractItemView {
      background-color: #1e1e2e;
      color: #cdd6f4;
      border: 1px solid #363a4f;
      selection-background-color: #ff7c00;
    }

    /* --- Check Boxes --- */
    QCheckBox {
      color: #cdd6f4;
      spacing: 8px;
    }
    QCheckBox::indicator {
      width: 18px;
      height: 18px;
      border-radius: 4px;
      border: 2px solid #585b70;
      background-color: #313244;
    }
    QCheckBox::indicator:checked {
      background-color: #ff7c00;
      border-color: #ff7c00;
    }
    QCheckBox::indicator:hover {
      border-color: #ff7c00;
    }

    /* --- Tab Widget --- */
    QTabWidget::pane {
      border: 1.5px solid #363a4f;
      border-radius: 8px;
      background-color: #24273a;
    }
    QTabBar::tab {
      background-color: #313244;
      color: #a6adc8;
      border-radius: 6px;
      padding: 6px 16px;
      margin: 2px;
      font-weight: 600;
    }
    QTabBar::tab:selected {
      background-color: #ff7c00;
      color: #ffffff;
    }
    QTabBar::tab:hover:!selected {
      background-color: #45475a;
      color: #cdd6f4;
    }

    /* --- Tool Tips --- */
    QToolTip {
      background-color: #313244;
      color: #cdd6f4;
      border: 1px solid #45475a;
      border-radius: 5px;
      padding: 4px 8px;
    }
  )");

  qInstallMessageHandler(deskflow::gui::messages::messageHandler);
  qInfo(DESKFLOW_APP_NAME " v%s", qPrintable(kVersion));

  dotenv();
  Logger::instance().loadEnvVars();

#if defined(Q_OS_MAC)

  if (app.applicationDirPath().startsWith("/Volumes/")) {
    QMessageBox::information(
        NULL, DESKFLOW_APP_NAME,
        "Please drag " DESKFLOW_APP_NAME " to the Applications folder, "
        "and open it from there."
    );
    return 1;
  }

  if (!checkMacAssistiveDevices()) {
    return 1;
  }
#endif

  Settings settings;
  if (settings.isUnavailable()) {
    messages::showPermissionError(
        nullptr, QString("read existing system settings, and user settings are not writable.")
    );
    return 1;
  }

  // --no-reset
  QStringList arguments = QCoreApplication::arguments();
  const auto noReset = hasArg("--no-reset", arguments);
  const auto resetEnvVar = strToTrue(qEnvironmentVariable("SYNERGY_RESET_ALL"));
  if (resetEnvVar && !noReset) {
    diagnostic::clearSettings(nullptr, settings, false);
  }

  AppConfig appConfig(settings);

  Logger::instance().setLogLevel(appConfig.logLevel());

  QObject::connect(
      &settings, &Settings::beforeSync, &appConfig, [&appConfig]() { appConfig.commit(); }, Qt::DirectConnection
  );

  if (appConfig.wizardShouldRun()) {
    SetupWizard wizard(appConfig);
    auto result = wizard.exec();
    if (result != QDialog::Accepted) {
      qInfo("wizard cancelled, exiting");
      return 0;
    }

    settings.sync();
  }

  if (appConfig.isSystemScope()) {
    if (!paths::persistSystemConfigDir()) {
      return 1;
    }
  } else {
    if (!paths::persistUserConfigDir()) {
      return 1;
    }
  }

  MainWindow mainWindow(settings, appConfig);

  QObject::connect(&app, &DeskflowApplication::aboutToQuit, &mainWindow, &MainWindow::onAppAboutToQuit);

  mainWindow.open();

#ifdef DESKFLOW_GUI_HOOK_APP_START
  DESKFLOW_GUI_HOOK_APP_START
#endif

#ifdef SYNERGY_VERSION_CHECK
  // It is important to check for updates after the start hook has run,
  // because the start hook may change the license.
  mainWindow.checkForUpdates();
#endif

  mainWindow.autoStartCore();

  return DeskflowApplication::exec();
}

#if defined(Q_OS_MAC)
bool checkMacAssistiveDevices()
{
#if __MAC_OS_X_VERSION_MIN_REQUIRED >= 1090 // mavericks

  // new in mavericks, applications are trusted individually
  // with use of the accessibility api. this call will show a
  // prompt which can show the security/privacy/accessibility
  // tab, with a list of allowed applications. deskflow should
  // show up there automatically, but will be unchecked.

  if (AXIsProcessTrusted()) {
    return true;
  }

  const void *keys[] = {kAXTrustedCheckOptionPrompt};
  const void *trueValue[] = {kCFBooleanTrue};
  CFDictionaryRef options = CFDictionaryCreate(NULL, keys, trueValue, 1, NULL, NULL);

  bool result = AXIsProcessTrustedWithOptions(options);
  CFRelease(options);
  return result;

#else

  // now deprecated in mavericks.
  bool result = AXAPIEnabled();
  if (!result) {
    QMessageBox::information(
        NULL, DESKFLOW_APP_NAME,
        "Please enable access to assistive devices "
        "System Preferences -> Security & Privacy -> "
        "Privacy -> Accessibility, then re-open " DESKFLOW_APP_NAME "."
    );
  }
  return result;

#endif
}
#endif
