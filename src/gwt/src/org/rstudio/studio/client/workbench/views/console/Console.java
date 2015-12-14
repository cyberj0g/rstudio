/*
 * Console.java
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.console;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.inject.Inject;

import org.rstudio.core.client.command.CommandBinder;
import org.rstudio.core.client.command.Handler;
import org.rstudio.core.client.dom.WindowEx;
import org.rstudio.core.client.layout.DelayFadeInHelper;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.workbench.commands.Commands;
import org.rstudio.studio.client.workbench.events.BusyEvent;
import org.rstudio.studio.client.workbench.events.BusyHandler;
import org.rstudio.studio.client.workbench.events.ZoomPaneEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleActivateEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsolePromptEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsolePromptHandler;
import org.rstudio.studio.client.workbench.views.console.events.SendToConsoleEvent;
import org.rstudio.studio.client.workbench.views.console.events.SendToConsoleHandler;
import org.rstudio.studio.client.workbench.views.environment.events.DebugModeChangedEvent;

public class Console
{
   interface Binder extends CommandBinder<Commands, Console> {}

   public interface Display
   {
      void bringToFront();
      void focus();
      void ensureCursorVisible();
      IsWidget getConsoleInterruptButton();
      void setDebugMode(boolean debugMode);
   }
   
   @Inject
   public Console(final Display view, EventBus events, Commands commands)
   {    
      view_ = view;
      events_ = events;

      events.addHandler(SendToConsoleEvent.TYPE, new SendToConsoleHandler()
      {
         public void onSendToConsole(SendToConsoleEvent event)
         {
            view.bringToFront();
         }
      });

      ((Binder) GWT.create(Binder.class)).bind(commands, this);

      fadeInHelper_ = new DelayFadeInHelper(
            view_.getConsoleInterruptButton().asWidget());
      events.addHandler(BusyEvent.TYPE, new BusyHandler()
      {
         @Override
         public void onBusy(BusyEvent event)
         {
            if (event.isBusy())
               fadeInHelper_.beginShow();
         }
      });

      events.addHandler(ConsolePromptEvent.TYPE, new ConsolePromptHandler()
      {
         @Override
         public void onConsolePrompt(ConsolePromptEvent event)
         {
            fadeInHelper_.hide();
         }
      });
      
      events.addHandler(DebugModeChangedEvent.TYPE, 
            new DebugModeChangedEvent.Handler()
      { 
         @Override
         public void onDebugModeChanged(DebugModeChangedEvent event)
         {
            view.setDebugMode(event.debugging());
         }
      });
      
      events.addHandler(ConsoleActivateEvent.TYPE, 
                        new ConsoleActivateEvent.Handler()
      {
         @Override
         public void onConsoleActivate(ConsoleActivateEvent event)
         {
            activateConsole(event.getFocusWindow());
         }
      });
   }

   @Handler
   void onActivateConsole()
   {
      activateConsole(true);
   }
   
   private void activateConsole(boolean focusWindow)
   {
      if (focusWindow)
         WindowEx.get().focus();
      
      view_.bringToFront();
      view_.focus();
      view_.ensureCursorVisible();
   }
   
   @Handler
   public void onLayoutZoomConsole()
   {
      onActivateConsole();
      events_.fireEvent(new ZoomPaneEvent("Console"));
   }
   
   public Display getDisplay()
   {
      return view_ ;
   }

   private final DelayFadeInHelper fadeInHelper_;
   private final EventBus events_;
   private final Display view_;
}
