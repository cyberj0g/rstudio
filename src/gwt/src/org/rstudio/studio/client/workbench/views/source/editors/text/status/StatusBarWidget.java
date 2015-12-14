/*
 * StatusBarWidget.java
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
package org.rstudio.studio.client.workbench.views.source.editors.text.status;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Style.Display;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.resources.client.ClientBundle;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Event.NativePreviewEvent;
import com.google.gwt.user.client.Event.NativePreviewHandler;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.*;

import org.rstudio.core.client.widget.IsWidgetWithHeight;
import org.rstudio.studio.client.common.icons.StandardIcons;
import org.rstudio.studio.client.common.icons.code.CodeIcons;

public class StatusBarWidget extends Composite
      implements StatusBar, IsWidgetWithHeight
{
   
   interface Binder extends UiBinder<HorizontalPanel, StatusBarWidget>
   {
   }

   public StatusBarWidget()
   {
      Binder binder = GWT.create(Binder.class);
      panel_ = binder.createAndBindUi(this);
      panel_.setVerticalAlignment(HorizontalPanel.ALIGN_TOP);
      
      panel_.setCellWidth(scope_, "100%");
      panel_.setCellWidth(message_, "100%");
      
      hideTimer_ = new Timer()
      {
         @Override
         public void run()
         {
            hideMessage();
         }
      };
      
      // The message widget should initially be hidden, but be shown in lieu of
      // the scope tree when requested.
      show(scope_);
      show(scopeIcon_);
      hide(message_);
   
      initWidget(panel_);

      height_ = 16;
   }
   
   // NOTE: The 'show' + 'hide' methods here take advantage of the fact that
   // status bar widgets live within table cells; to ensure proper sizing we
   // need to set the display property on those cells rather than the widgets
   // themselves.
   private void hide(Widget widget)
   {
      widget.setVisible(false);
      widget.getElement().getParentElement().getStyle().setDisplay(Display.NONE);
   }
   
   private void show(Widget widget)
   {
      widget.setVisible(true);
      widget.getElement().getParentElement().getStyle().clearDisplay();
   }

   public int getHeight()
   {
      return height_;
   }

   public Widget asWidget()
   {
      return this;
   }

   public StatusBarElement getPosition()
   {
      return position_;
   }
   
   public StatusBarElement getMessage()
   {
      return message_;
   }

   public StatusBarElement getScope()
   {
      return scope_;
   }

   public StatusBarElement getLanguage()
   {
      return language_;
   }

   public void setScopeVisible(boolean visible)
   {
      scope_.setClicksEnabled(visible);
      scope_.setContentsVisible(visible);
      scopeIcon_.setVisible(visible);
   }
   
   public void setScopeType(int type)
   {
      scopeType_ = type;
      if (type == StatusBar.SCOPE_TOP_LEVEL || message_.isVisible())
         scopeIcon_.setVisible(false);
      else
         scopeIcon_.setVisible(true);
         
           if (type == StatusBar.SCOPE_CLASS)
         scopeIcon_.setResource(CodeIcons.INSTANCE.clazz());
      else if (type == StatusBar.SCOPE_NAMESPACE)
         scopeIcon_.setResource(CodeIcons.INSTANCE.namespace());
      else if (type == StatusBar.SCOPE_LAMBDA)
         scopeIcon_.setResource(StandardIcons.INSTANCE.lambdaLetter());
      else if (type == StatusBar.SCOPE_ANON)
         scopeIcon_.setResource(StandardIcons.INSTANCE.functionLetter());
      else if (type == StatusBar.SCOPE_FUNCTION)
         scopeIcon_.setResource(StandardIcons.INSTANCE.functionLetter());
      else if (type == StatusBar.SCOPE_CHUNK)
         scopeIcon_.setResource(RES.chunk());
      else if (type == StatusBar.SCOPE_SECTION)
         scopeIcon_.setResource(RES.section());
      else if (type == StatusBar.SCOPE_SLIDE)
         scopeIcon_.setResource(RES.slide());
      else
         scopeIcon_.setResource(CodeIcons.INSTANCE.function());
   }
   
   private void initMessage(String message)
   {
      hide(scope_);
      hide(scopeIcon_);
      
      message_.setValue(message);
      show(message_);
   }
   
   private void endMessage()
   {
      show(scope_);
      show(scopeIcon_);
      hide(message_);
      
      setScopeType(scopeType_);
   }
   
   @Override
   public void showMessage(String message)
   {
      initMessage(message);
   }
   
   @Override
   public void showMessage(String message, int timeMs)
   {
      initMessage(message);
      hideTimer_.schedule(timeMs);
   }
   
   @Override
   public void showMessage(String message, final HideMessageHandler handler)
   {
      initMessage(message);
      
      // Protect against multiple messages shown at same time
      if (handler_ != null)
      {
         handler_.removeHandler();
         handler_ = null;
      }
      
      handler_ = Event.addNativePreviewHandler(new NativePreviewHandler()
      {
         @Override
         public void onPreviewNativeEvent(NativePreviewEvent event)
         {
            if (handler.onNativePreviewEvent(event))
            {
               endMessage();
               handler_.removeHandler();
               handler_ = null;
            }
         }
      });
   }
   
   @Override
   public void hideMessage()
   {
      endMessage();
   }

   @UiField StatusBarElementWidget position_;
   @UiField StatusBarElementWidget scope_;
   @UiField StatusBarElementWidget message_;
   @UiField StatusBarElementWidget language_;
   @UiField Image scopeIcon_;
   
   public interface Resources extends ClientBundle
   {
      ImageResource chunk();
      ImageResource section();
      ImageResource slide();
   }
   
   public static Resources RES = GWT.create(Resources.class);
   private final HorizontalPanel panel_;
   private final Timer hideTimer_;
   
   private int height_;
   private HandlerRegistration handler_;
   private int scopeType_;
   
}
