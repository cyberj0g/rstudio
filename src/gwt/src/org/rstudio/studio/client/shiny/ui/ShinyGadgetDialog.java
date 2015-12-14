/*
 * ShinyGadgetDialog.java
 *
 * Copyright (C) 2009-14 by RStudio, Inc.
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

package org.rstudio.studio.client.shiny.ui;

import org.rstudio.core.client.Size;
import org.rstudio.core.client.dom.DomMetrics;
import org.rstudio.core.client.widget.ModalDialogBase;
import org.rstudio.core.client.widget.RStudioFrame;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.Desktop;
import org.rstudio.studio.client.workbench.commands.Commands;

import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;

public class ShinyGadgetDialog extends ModalDialogBase
{
   public ShinyGadgetDialog(String caption, String url, Size preferredSize)
   {
      RStudioGinjector.INSTANCE.injectMembers(this);
      setText(caption);
      url_ = url;
      preferredSize_ = preferredSize;
      initializeEvents();
   }
   
   @Inject
   void initialize(Commands commands)
   {
      commands_ = commands;
   }
   
   @Override
   protected Widget createMainWidget()
   {
      frame_ = new RStudioFrame();
      
      // compute the widget size and set it
      Size minimumSize = new Size(300, 300);
      Size size = DomMetrics.adjustedElementSize(preferredSize_, 
                                                 minimumSize, 
                                                 0,   // pad
                                                 100); // client margin
      frame_.setSize(size.width + "px", size.height + "px");
      
      if (Desktop.isDesktop())
         Desktop.getFrame().setShinyDialogUrl(url_);
      
      frame_.setUrl(url_);
      return frame_;
   }
   
   @Override
   protected void onDialogShown()
   {
      super.onDialogShown();
      frame_.getWindow().focus();
   }
   
   private native void initializeEvents() /*-{  
      var thiz = this;   
      var handler = $entry(function(e) {
         if (typeof e.data != 'string')
            return;
         thiz.@org.rstudio.studio.client.shiny.ui.ShinyGadgetDialog::onMessage(Ljava/lang/String;Ljava/lang/String;)(e.data, e.origin);
         $wnd.removeEventListener('message', handler, false);
      });
      $wnd.addEventListener("message", handler, true);
   }-*/;

   private void onMessage(String data, String origin)
   {  
      if ("disconnected".equals(data))
      {
         // ensure the frame url starts with the specified origin
         if (frame_.getUrl().startsWith(origin))
            closeDialog();
         
         // interrupt R if needed
         if (commands_.interruptR().isEnabled())
            commands_.interruptR().execute();
      }
   }

   private final String url_;
   private Size preferredSize_;
   private RStudioFrame frame_;
   private Commands commands_;
}
