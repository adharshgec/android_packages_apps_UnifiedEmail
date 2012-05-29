/**
 * Copyright (c) 2011, Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.mail.compose;

import com.android.mail.R;
import com.android.mail.providers.Account;
import com.android.mail.providers.Attachment;
import com.android.mail.providers.Message;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

/*
 * View for displaying attachments in the compose screen.
 */
class AttachmentsView extends LinearLayout {
    private static final String LOG_TAG = new LogUtils().getLogTag();
    private ArrayList<Attachment> mAttachments;
    private AttachmentDeletedListener mChangeListener;

    public AttachmentsView(Context context) {
        this(context, null);
    }

    public AttachmentsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mAttachments = Lists.newArrayList();
    }

    /**
     * Set a listener for changes to the attachments.
     * @param listener
     */
    public void setAttachmentChangesListener(AttachmentDeletedListener listener) {
        mChangeListener = listener;
    }

    /**
     * Add an attachment and update the ui accordingly.
     * @param attachment
     */
    public void addAttachment(final Attachment attachment) {
        if (!isShown()) {
            setVisibility(View.VISIBLE);
        }
        mAttachments.add(attachment);

        final AttachmentComposeView attachmentView =
            new AttachmentComposeView(getContext(), attachment);

        attachmentView.addDeleteListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteAttachment(attachmentView, attachment);
            }
        });


        addView(attachmentView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
    }

    @VisibleForTesting
    protected void deleteAttachment(final AttachmentComposeView attachmentView,
            final Attachment attachment) {
        mAttachments.remove(attachment);
        removeView(attachmentView);
        if (mChangeListener != null) {
            mChangeListener.onAttachmentDeleted();
        }
        if (mAttachments.size() == 0) {
            setVisibility(View.GONE);
        }
    }

    /**
     * Get all attachments being managed by this view.
     * @return attachments.
     */
    public ArrayList<Attachment> getAttachments() {
        return mAttachments;
    }

    /**
     * Delete all attachments being managed by this view.
     */
    public void deleteAllAttachments() {
        mAttachments.clear();
        removeAllViews();
    }

    /**
     * Get the total size of all attachments currently in this view.
     */
    public long getTotalAttachmentsSize() {
        long totalSize = 0;
        for (Attachment attachment : mAttachments) {
            totalSize += attachment.size;
        }
        return totalSize;
    }

    /**
     * Interface to implement to be notified about changes to the attachments.
     *
     */
    public interface AttachmentDeletedListener {
        public void onAttachmentDeleted();
    }

    /**
     * When an attachment is too large to be added to a message, show a toast.
     * This method also updates the position of the toast so that it is shown
     * clearly above they keyboard if it happens to be open.
     */
    private void showAttachmentTooBigToast() {
        showErrorToast(R.string.too_large_to_attach);
    }

    private void showGenericAttachmentError() {
        showErrorToast(R.string.generic_attachment_problem);
    }

    private void showErrorToast(int resId) {
        Toast t = Toast.makeText(getContext(), resId,
                Toast.LENGTH_LONG);
        t.setText(resId);
        t.setGravity(Gravity.CENTER_HORIZONTAL, 0,
                getResources().getDimensionPixelSize(R.dimen.attachment_toast_yoffset));
        t.show();
    }

    /**
     * Generate an {@link Attachment} object for a given local content URI. Attempts to populate
     * the {@link Attachment#name}, {@link Attachment#size}, and {@link Attachment#contentType}
     * fields using a {@link ContentResolver}.
     *
     * @param contentUri
     * @return an Attachment object
     * @throws AttachmentFailureException
     */
    public Attachment generateLocalAttachment(Uri contentUri) throws AttachmentFailureException {
        // FIXME: do not query resolver for type on the UI thread
        final ContentResolver contentResolver = getContext().getContentResolver();
        String contentType = contentResolver.getType(contentUri);
        if (contentUri == null || TextUtils.isEmpty(contentUri.getPath())) {
            showGenericAttachmentError();
            throw new AttachmentFailureException("Attachment too large to attach");
        }

        if (contentType == null) contentType = "";

        final Attachment attachment = new Attachment();
        attachment.uri = null; // URI will be assigned by the provider upon send/save
        attachment.name = null;
        attachment.contentType = contentType;
        attachment.size = 0;
        attachment.contentUri = contentUri;

        Cursor metadataCursor = null;
        try {
            metadataCursor = contentResolver.query(
                    contentUri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null);
            if (metadataCursor != null) {
                try {
                    if (metadataCursor.moveToNext()) {
                        attachment.name = metadataCursor.getString(0);
                        attachment.size = metadataCursor.getInt(1);
                    }
                } finally {
                    metadataCursor.close();
                }
            }
        } catch (SQLiteException ex) {
            // One of the two columns is probably missing, let's make one more attempt to get at
            // least one.
            // Note that the documentations in Intent#ACTION_OPENABLE and
            // OpenableColumns seem to contradict each other about whether these columns are
            // required, but it doesn't hurt to fail properly.

            // Let's try to get DISPLAY_NAME
            try {
                metadataCursor = getOptionalColumn(contentResolver, contentUri,
                        OpenableColumns.DISPLAY_NAME);
                if (metadataCursor != null && metadataCursor.moveToNext()) {
                    attachment.name = metadataCursor.getString(0);
                }
            } finally {
                if (metadataCursor != null) metadataCursor.close();
            }

            // Let's try to get SIZE
            try {
                metadataCursor =
                        getOptionalColumn(contentResolver, contentUri, OpenableColumns.SIZE);
                if (metadataCursor != null && metadataCursor.moveToNext()) {
                    attachment.size = metadataCursor.getInt(0);
                } else {
                    // Unable to get the size from the metadata cursor. Open the file and seek.
                    attachment.size = getSizeFromFile(contentUri, contentResolver);
                }
            } finally {
                if (metadataCursor != null) metadataCursor.close();
            }
        } catch (SecurityException e) {
            // We received a security exception when attempting to add an
            // attachment.  Warn the user.
            // TODO(pwestbro): determine if we need more specific text in the toast.
            Toast.makeText(getContext(),
                    R.string.generic_attachment_problem, Toast.LENGTH_LONG).show();
            throw new AttachmentFailureException("Security Exception from attachment uri", e);
        }

        if (attachment.name == null) {
            attachment.name = contentUri.getLastPathSegment();
        }

        return attachment;
    }

    /**
     * Adds a local attachment by file path.
     * @param account
     * @param contentUri the uri of the local file path
     *
     * @return size of the attachment added.
     * @throws AttachmentFailureException if an error occurs adding the attachment.
     */
    public long addAttachment(Account account, Uri contentUri)
            throws AttachmentFailureException {
        return addAttachment(account, generateLocalAttachment(contentUri));
    }

    /**
     * Adds an attachment of either local or remote origin, checking to see if the attachment
     * exceeds file size limits.
     * @param account
     * @param attachment the attachment to be added.
     *
     * @return size of the attachment added.
     * @throws AttachmentFailureException if an error occurs adding the attachment.
     */
    public long addAttachment(Account account, Attachment attachment)
            throws AttachmentFailureException {
        int maxSize = UIProvider.getMailMaxAttachmentSize(account.name);

        // Error getting the size or the size was too big.
        // FIXME: exceptions should not be used to direct control flow
        if (attachment.size == -1 || attachment.size > maxSize) {
            showAttachmentTooBigToast();
            throw new AttachmentFailureException("Attachment too large to attach");
        } else if ((getTotalAttachmentsSize()
                + attachment.size) > maxSize) {
            showAttachmentTooBigToast();
            throw new AttachmentFailureException("Attachment too large to attach");
        } else {
            addAttachment(attachment);
        }

        return attachment.size;
    }


    public void addAttachments(Account account, Message refMessage) {
        if (refMessage.hasAttachments) {
            try {
                for (Attachment a : refMessage.getAttachments()) {
                    addAttachment(account, a);
                }
            } catch (AttachmentFailureException e) {
                // A toast has already been shown to the user, no need to do
                // anything.
                LogUtils.e(LOG_TAG, e, "Error adding attachment");
            }
        }
    }

    @VisibleForTesting
    protected int getSizeFromFile(Uri uri, ContentResolver contentResolver) {
        int size = -1;
        ParcelFileDescriptor file = null;
        try {
            file = contentResolver.openFileDescriptor(uri, "r");
            size = (int) file.getStatSize();
        } catch (FileNotFoundException e) {
            LogUtils.w(LOG_TAG, "Error opening file to obtain size.");
        } finally {
            try {
                if (file != null) {
                    file.close();
                }
            } catch (IOException e) {
                LogUtils.w(LOG_TAG, "Error closing file opened to obtain size.");
            }
        }
        return size;
    }

    /**
     * @return a cursor to the requested column or null if an exception occurs while trying
     * to query it.
     */
    private Cursor getOptionalColumn(ContentResolver contentResolver, Uri uri, String columnName) {
        Cursor result = null;
        try {
            result = contentResolver.query(uri, new String[]{columnName}, null, null, null);
        } catch (SQLiteException ex) {
            // ignore, leave result null
        }
        return result;
    }

    /**
     * Class containing information about failures when adding attachments.
     */
    static class AttachmentFailureException extends Exception {
        private static final long serialVersionUID = 1L;

        public AttachmentFailureException(String error) {
            super(error);
        }
        public AttachmentFailureException(String detailMessage, Throwable throwable) {
            super(detailMessage, throwable);
        }
    }
}
