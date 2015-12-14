/*
 * ShortcutManager.java
 *
 * Copyright (C) 2009-15 by RStudio, Inc.
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
package org.rstudio.core.client.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rstudio.core.client.BrowseCap;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.command.KeyMap.CommandBinding;
import org.rstudio.core.client.command.KeyMap.KeyMapType;
import org.rstudio.core.client.command.KeyboardShortcut.KeyCombination;
import org.rstudio.core.client.command.KeyboardShortcut.KeySequence;
import org.rstudio.core.client.events.NativeKeyDownEvent;
import org.rstudio.core.client.events.NativeKeyDownHandler;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.workbench.addins.AddinsCommandManager;
import org.rstudio.studio.client.workbench.commands.RStudioCommandExecutedFromShortcutEvent;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.AceKeyboardActivityEvent;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Event.NativePreviewEvent;
import com.google.gwt.user.client.Event.NativePreviewHandler;
import com.google.gwt.user.client.Timer;
import com.google.inject.Inject;

public class ShortcutManager implements NativePreviewHandler,
                                        NativeKeyDownHandler
{
   public interface Handle
   {
      void close();
   }

   public static final ShortcutManager INSTANCE = new ShortcutManager();

   private ShortcutManager()
   {
      keyBuffer_ = new KeySequence();
      keyTimer_ = new Timer()
      {
         @Override
         public void run()
         {
            keyBuffer_.clear();
         }
      };
      
      shortcutInfo_ = new ArrayList<ShortcutInfo>();
      
      // Initialize the key maps. We use a LinkedHashMap so that insertion
      // order can be preserved.
      keyMaps_ = new LinkedHashMap<KeyMapType, KeyMap>();
      for (KeyMapType type : KeyMapType.values())
         keyMaps_.put(type, new KeyMap());
      
      // Defer injection because the ShortcutManager is constructed
      // very eagerly (to allow for codegen stuff in ShortcutsEmitter
      // to work)
      Scheduler.get().scheduleDeferred(new ScheduledCommand()
      {
         @Override
         public void execute()
         {
            RStudioGinjector.INSTANCE.injectMembers(ShortcutManager.this);
            events_.addHandler(
                  AceKeyboardActivityEvent.TYPE,
                  new AceKeyboardActivityEvent.Handler()
                  {
                     @Override
                     public void onAceKeyboardActivity(AceKeyboardActivityEvent event)
                     {
                        if (!event.isChainEvent())
                           keyBuffer_.clear();
                     }
                  });
         }
      });
      
      // NOTE: Because this class is used as a singleton and is never
      // destroyed it's not necessary to manage lifetime of this event handler
      Event.addNativePreviewHandler(this);
      addPostViewHandler();
   }
   
   private native final void addPostViewHandler() /*-{
      var self = this;
      $doc.body.addEventListener("keydown", $entry(function(evt) {
         self.@org.rstudio.core.client.command.ShortcutManager::swallowEvents(Ljava/lang/Object;)(evt);
      }));
   }-*/;
   
   @Inject
   private void initialize(ApplicationCommandManager appCommands,
                           EditorCommandManager editorCommands,
                           UserCommandManager userCommands,
                           AddinsCommandManager addins,
                           EventBus events)
   {
      appCommands_ = appCommands;
      editorCommands_ = editorCommands;
      userCommands_ = userCommands;
      addins_ = addins;
      events_ = events;
   }

   public boolean isEnabled()
   {
      return disableCount_ == 0;
   }

   public Handle disable()
   {
      disableCount_++;
      return new Handle()
      {
         private boolean closed_ = false;

         @Override
         public void close()
         {
            if (!closed_)
               disableCount_--;
            closed_ = true;
         }
      };
   }
   
   public void register(int modifiers, 
                        int keyCode, 
                        AppCommand command, 
                        String groupName, 
                        String title,
                        String disableModes)
   {
      if (!BrowseCap.hasMetaKey() && (modifiers & KeyboardShortcut.META) != 0)
         return;
      
      register(
            new KeySequence(keyCode, modifiers),
            command,
            groupName,
            title,
            disableModes);
   }
   
   public void register(int m1,
                        int k1,
                        int m2,
                        int k2,
                        AppCommand command,
                        String groupName,
                        String title,
                        String disableModes)
   {
      KeySequence sequence = new KeySequence();
      sequence.add(k1, m1);
      sequence.add(k2, m2);
      register(sequence, command, groupName, title, disableModes);
   }
   
   public void register(KeySequence keys, AppCommand command)
   {
      register(keys, command, "", "", "");
   }
   
   // Registering a keyboard shortcut needs to perform two actions:
   //
   // 1. Register information about a particular command; e.g. the name,
   //    description, and so on. This is done to help power UI (e.g. menu
   //    labels, shortcut quick ref, and so on).
   //
   // 2. Actually bind a command to a particular key sequence. This binding
   //    might only be active in certain contexts (e.g. in Emacs mode), and
   //
   // Note that the above two actions are separate since some commands we
   // register are only done for documentation / powering the UI. Ie, some
   // shortcuts might be created entirely so that they can power UI, without
   // actually directly becoming part of the command system.
   public void register(KeySequence keys,
                        AppCommand command,
                        String groupName,
                        String title,
                        String disableModes)
   {
      // Register the keyboard shortcut information.
      KeyboardShortcut shortcut = new KeyboardShortcut(keys, groupName, title, disableModes);
      shortcutInfo_.add(new ShortcutInfo(shortcut, command));
      
      // Bind the command in the application key map.
      if (command != null)
      {
         // Setting the shortcut on the command just registers this binding as the
         // default shortcut for the command. This allows UI (e.g. menu items) to easily
         // look up and display an active shortcut, without displaying _all_ active shortcuts.
         command.setShortcut(shortcut);
         
         // Add the command into the keymap, ensuring it can be executed on the associated
         // keypress.
         KeyMap appKeyMap = keyMaps_.get(KeyMapType.APPLICATION);
         appKeyMap.addBinding(keys, new AppCommandBinding(command, disableModes, false));
      }
   }
   
   public static int parseDisableModes(String disableModes)
   {
      int mode = KeyboardShortcut.MODE_NONE;
      
      if (StringUtil.isNullOrEmpty(disableModes))
         return mode;
      
      String[] splat = disableModes.split(",");
      for (String item : splat)
      {
         if (item.equals("default"))
            mode |= KeyboardShortcut.MODE_DEFAULT;
         else if (item.equals("vim"))
            mode |= KeyboardShortcut.MODE_VIM;
         else if (item.equals("emacs"))
            mode |= KeyboardShortcut.MODE_EMACS;
         else
            assert false: "Unrecognized 'disableModes' value '" + item + "'";
      }
      
      return mode;
   }
   
   public void onKeyDown(NativeKeyDownEvent evt)
   {
      if (evt.isCanceled())
         return;

      keyTimer_.schedule(3000);
      if (handleKeyDown(evt.getEvent()))
      {
         evt.cancel();
         events_.fireEvent(new RStudioCommandExecutedFromShortcutEvent());
      }
   }

   public void onPreviewNativeEvent(NativePreviewEvent event)
   {
      if (event.isCanceled())
         return;

      keyTimer_.schedule(3000);
      if (event.getTypeInt() == Event.ONKEYDOWN)
      {
         if (handleKeyDown(event.getNativeEvent()))
         {
            event.cancel();
            events_.fireEvent(new RStudioCommandExecutedFromShortcutEvent());
         }
      }
   }
   
   public void setEditorMode(int editorMode)
   {
      editorMode_ = editorMode;
   }
   
   public int getEditorMode()
   {
      return editorMode_;
   }
   
   public List<ShortcutInfo> getActiveShortcutInfo()
   {
      // Filter out commands disabled due to the current editor mode.
      // Also only retain the first discovered binding for a particular command.
      final Set<String> encounteredShortcuts = new HashSet<String>();
      
      List<ShortcutInfo> filtered = new ArrayList<ShortcutInfo>();
      for (int i = 0, n = shortcutInfo_.size(); i < n; i++)
      {
         ShortcutInfo object = shortcutInfo_.get(n - i - 1);
         if (encounteredShortcuts.contains(object.getDescription()))
            continue;
         
         boolean isEnabled = (object.getDisableModes() & editorMode_) == 0;
         if (isEnabled)
         {
            encounteredShortcuts.add(object.getDescription());
            filtered.add(object);
         }
      }
      
      // Sort in order declared in Commands.cmd.xml
      Collections.sort(filtered, new Comparator<ShortcutInfo>()
      {
         @Override
         public int compare(ShortcutInfo o1, ShortcutInfo o2)
         {
            return o1.getOrder() - o2.getOrder();
         }
      });
      
      return filtered;
   }

   private boolean handleKeyDown(NativeEvent event)
   {
      // Bail if the shortcut manager is not enabled (e.g.
      // we disable it temporarily when interacting with
      // modal dialogs)
      if (!isEnabled())
         return false;
      
      // Don't dispatch on bare modifier keypresses.
      if (KeyboardHelper.isModifierKey(event.getKeyCode()))
         return false;
      
      // Escape key should always clear the keybuffer.
      if (event.getKeyCode() == KeyCodes.KEY_ESCAPE)
      {
         keyBuffer_.clear();
         return false;
      }
      
      keyBuffer_.add(event);
      
      // Loop through all active key maps, and attempt to find an active
      // binding. 'pending' is used to indicate whether there are any bindings
      // following the current state of the keybuffer.
      boolean pending = false;
      for (Map.Entry<KeyMapType, KeyMap> entry : keyMaps_.entrySet())
      {
         KeyMap map = entry.getValue();
         CommandBinding binding = map.getActiveBinding(keyBuffer_);
         if (binding != null)
         {
            keyBuffer_.clear();
            event.stopPropagation();
            binding.execute();
            return true;
         }
         
         if (map.isPrefix(keyBuffer_))
            pending = true;
      }
      
      if (!pending)
         keyBuffer_.clear();
      
      // Assume that a keypress without a modifier key clears the keybuffer.
      // This disallows binding of commands in a way like '<SPC> a a', which
      // kind of stinks, but helps ensure that we don't get a stale keybuffer.
      // This code could be removed if we could reliably detect whether an
      // underlying editor instance handled the key combination, but there seem
      // to be cased where Ace doesn't report handling a keypress (e.g. arrow keys,
      // 'I', and some other cases)
      if (!keyBuffer_.isEmpty())
      {
         KeyCombination keys = keyBuffer_.get(keyBuffer_.size() - 1);
         if (keys.getModifier() == KeyboardShortcut.NONE)
            keyBuffer_.clear();
      }
      
      return false;
   }
   
   private void swallowEvents(Object object)
   {
      NativeEvent event = (NativeEvent) object;
      
      // Suppress save / quit events from reaching the browser
      KeyCombination keys = new KeyCombination(event);
      int keyCode = keys.getKeyCode();
      int modifiers = keys.getModifier();
      
      boolean isSaveQuitKey =
            keyCode == KeyCodes.KEY_S ||
            keyCode == KeyCodes.KEY_W;
      
      boolean isSaveQuitModifier = BrowseCap.isMacintosh() ?
            modifiers == KeyboardShortcut.META :
            modifiers == KeyboardShortcut.CTRL;
      
      if (isSaveQuitKey && isSaveQuitModifier)
         event.preventDefault();
   }
   
   public KeyMap getKeyMap(KeyMapType type)
   {
      return keyMaps_.get(type);
   }
   
   private int disableCount_ = 0;
   private int editorMode_ = KeyboardShortcut.MODE_DEFAULT;
   
   private final KeySequence keyBuffer_;
   private final Timer keyTimer_;
   
   private final Map<KeyMapType, KeyMap> keyMaps_;
   private final List<ShortcutInfo> shortcutInfo_;
   
   // Injected ----
   private UserCommandManager userCommands_;
   private EditorCommandManager editorCommands_;
   private ApplicationCommandManager appCommands_;
   private AddinsCommandManager addins_;
   private EventBus events_;
   
}
