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
      background-color: #0f1015;
      color: #f1f2f6;
      font-family: "Inter", "Segoe UI", "San Francisco", -apple-system, sans-serif;
      font-size: 13px;
    }

    /* --- Radio Buttons --- */
    QRadioButton {
      color: #e2e4ec;
      font-size: 13px;
      font-weight: 600;
      spacing: 12px;
      padding: 6px 0px;
    }
    QRadioButton::indicator {
      width: 18px;
      height: 18px;
      border-radius: 9px;
      border: 2px solid #3b3f54;
      background-color: #1a1b23;
    }
    QRadioButton::indicator:hover {
      border-color: #ff8a00;
      background-color: #232530;
    }
    QRadioButton::indicator:checked {
      border: 2px solid #ff7900;
      background-color: #ff7900;
      image: url(none);
    }
    QRadioButton::indicator:checked:hover {
      border-color: #ff9d33;
      background-color: #ff9d33;
    }
    QRadioButton:disabled {
      color: #8c91a5;
    }

    /* --- Group Boxes --- */
    QGroupBox {
      background-color: #171821;
      border: 1px solid #282a36;
      border-radius: 8px;
      margin-top: 20px;
      padding: 18px 14px 14px 14px;
      font-size: 13px;
      font-weight: 700;
      color: #a0a5b5;
    }
    QGroupBox::title {
      subcontrol-origin: margin;
      subcontrol-position: top left;
      left: 14px;
      top: -2px;
      padding: 2px 8px;
      color: #f1f2f6;
      font-size: 11px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 1.2px;
      background-color: #171821;
      border: 1px solid #282a36;
      border-radius: 4px;
    }

    /* --- Push Buttons --- */
    QPushButton {
      background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
        stop:0 #ff9500, stop:1 #ff7200);
      color: #ffffff;
      border: none;
      border-radius: 6px;
      padding: 8px 20px;
      font-size: 13px;
      font-weight: 600;
      min-width: 80px;
    }
    QPushButton:hover {
      background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
        stop:0 #ffa626, stop:1 #ff8314);
    }
    QPushButton:pressed {
      background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
        stop:0 #e66600, stop:1 #cc5200);
      padding-top: 9px;
      padding-bottom: 7px;
    }
    QPushButton:disabled {
      background: #252631;
      color: #8c91a5;
    }

    /* --- Line Edits --- */
    QLineEdit {
      background-color: #1a1b23;
      color: #f1f2f6;
      border: 1px solid #2d303f;
      border-radius: 6px;
      padding: 6px 12px;
      font-size: 13px;
      selection-background-color: #ff7200;
      selection-color: #ffffff;
    }
    QLineEdit:focus {
      border-color: #ff7200;
      background-color: #1f202b;
    }
    QLineEdit:disabled {
      background-color: #121319;
      color: #8c91a5;
      border-color: #1f202b;
    }

    /* --- Table / List / Tree Views --- */
    QTableView, QTreeView, QListView, QListWidget {
      background-color: #12131a;
      gridline-color: #282a36;
      border: 1px solid #282a36;
      border-radius: 8px;
      color: #f1f2f6;
    }
    QTableView::item, QTreeView::item, QListView::item, QListWidget::item {
      color: #f1f2f6;
    }
    QTableView::item:selected, QTreeView::item:selected, QListView::item:selected, QListWidget::item:selected {
      background-color: #ff7200;
      color: #ffffff;
    }

    /* --- Labels --- */
    QLabel {
      color: #e2e4ec;
      background: transparent;
    }

    /* --- Log Output --- */
    QPlainTextEdit {
      background-color: #08090c;
      color: #5af78e;
      border: 1px solid #1c1d24;
      border-radius: 6px;
      padding: 8px;
      font-family: "Fira Code", "JetBrains Mono", "Courier New", monospace;
      font-size: 12px;
      selection-background-color: #ff7200;
    }

    /* --- Menu Bar --- */
    QMenuBar {
      background-color: #0b0c0f;
      color: #e2e4ec;
      border-bottom: 1px solid #1c1d24;
      padding: 4px;
    }
    QMenuBar::item:selected {
      background-color: #1c1d24;
      border-radius: 4px;
    }
    QMenu {
      background-color: #12131a;
      color: #f1f2f6;
      border: 1px solid #282a36;
      border-radius: 6px;
      padding: 5px;
    }
    QMenu::item:selected {
      background-color: #ff7200;
      color: #ffffff;
      border-radius: 4px;
    }

    /* --- Scroll Bars --- */
    QScrollBar:vertical {
      background: #0b0c0f;
      width: 10px;
      border-radius: 5px;
      margin: 2px;
    }
    QScrollBar::handle:vertical {
      background: #2d303f;
      border-radius: 5px;
      min-height: 20px;
    }
    QScrollBar::handle:vertical:hover {
      background: #ff7200;
    }
    QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
      height: 0px;
    }
    QScrollBar:horizontal {
      background: #0b0c0f;
      height: 10px;
      border-radius: 5px;
      margin: 2px;
    }
    QScrollBar::handle:horizontal {
      background: #2d303f;
      border-radius: 5px;
      min-width: 20px;
    }
    QScrollBar::handle:horizontal:hover {
      background: #ff7200;
    }

    /* --- Combo Boxes --- */
    QComboBox {
      background-color: #1a1b23;
      color: #f1f2f6;
      border: 1px solid #2d303f;
      border-radius: 6px;
      padding: 6px 12px;
      font-size: 13px;
    }
    QComboBox:focus {
      border-color: #ff7200;
    }
    QComboBox:disabled {
      background-color: #121319;
      color: #8c91a5;
      border-color: #1f202b;
    }
    QComboBox::drop-down {
      border: none;
      padding-right: 10px;
    }
    QComboBox QAbstractItemView {
      background-color: #12131a;
      color: #f1f2f6;
      border: 1px solid #282a36;
      selection-background-color: #ff7200;
    }

    /* --- Check Boxes --- */
    QCheckBox {
      color: #e2e4ec;
      spacing: 8px;
    }
    QCheckBox::indicator {
      width: 16px;
      height: 16px;
      border-radius: 4px;
      border: 2px solid #3b3f54;
      background-color: #1a1b23;
    }
    QCheckBox::indicator:checked {
      background-color: #ff7200;
      border-color: #ff7200;
    }
    QCheckBox::indicator:hover {
      border-color: #ff7200;
    }

    /* --- Tab Widget --- */
    QTabWidget::pane {
      border: 1px solid #282a36;
      border-radius: 8px;
      background-color: #171821;
    }
    QTabBar::tab {
      background-color: #1a1b23;
      color: #a0a5b5;
      border-radius: 5px;
      padding: 8px 18px;
      margin: 3px;
      font-weight: 600;
    }
    QTabBar::tab:selected {
      background-color: #ff7200;
      color: #ffffff;
    }
    QTabBar::tab:hover:!selected {
      background-color: #2d303f;
      color: #f1f2f6;
    }

    /* --- Tool Tips --- */
    QToolTip {
      background-color: #1a1b23;
      color: #f1f2f6;
      border: 1px solid #2d303f;
      border-radius: 4px;
      padding: 6px 10px;
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
