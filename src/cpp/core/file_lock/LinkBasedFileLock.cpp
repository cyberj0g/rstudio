/*
 * LinkBasedFileLock.cpp
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

#include <core/FileLock.hpp>

#include <errno.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>

#include <set>
#include <vector>

#include <core/SafeConvert.hpp>
#include <core/Algorithm.hpp>
#include <core/Thread.hpp>
#include <core/Error.hpp>
#include <core/Log.hpp>
#include <core/FilePath.hpp>
#include <core/FileSerializer.hpp>
#include <core/system/System.hpp>

#include <boost/foreach.hpp>
#include <boost/system/error_code.hpp>

namespace rstudio {
namespace core {

namespace {

const char * const kFileLockPrefix =
      ".rstudio-lock-41c29";

std::string pidString()
{
   PidType pid = system::currentProcessId();
   return safe_convert::numberToString((long) pid);
}

std::string hostName()
{
   char buffer[256];
   int status = ::gethostname(buffer, 255);
   if (status)
      LOG_ERROR(systemError(errno, ERROR_LOCATION));
   return std::string(buffer);
}

std::string threadId()
{
   std::stringstream ss;
   ss << boost::this_thread::get_id();
   return ss.str();
}

std::string proxyLockFileName()
{
   return std::string()
         + kFileLockPrefix
         + "-" + hostName()
         + "-" + pidString()
         + "-" + threadId();
         
}

bool isLockFileStale(const FilePath& lockFilePath)
{
   return LinkBasedFileLock::isLockFileStale(lockFilePath);
}

} // end anonymous namespace

bool LinkBasedFileLock::isLockFileStale(const FilePath& lockFilePath)
{
   double seconds = s_timeoutInterval.total_seconds();
   double diff = ::difftime(::time(NULL), lockFilePath.lastWriteTime());
   return diff >= seconds;
}

namespace {

void cleanStaleLockfiles(const FilePath& dir)
{
   std::vector<FilePath> children;
   Error error = dir.children(&children);
   if (error)
      LOG_ERROR(error);
   
   BOOST_FOREACH(const FilePath& filePath, children)
   {
      if (boost::algorithm::starts_with(filePath.filename(), kFileLockPrefix) &&
          isLockFileStale(filePath))
      {
         Error error = filePath.removeIfExists();
         if (error)
            LOG_ERROR(error);
      }
   }
}

class LockRegistration : boost::noncopyable
{
public:
   
   void registerLock(const FilePath& lockFilePath)
   {
      LOCK_MUTEX(mutex_)
      {
         registration_.insert(lockFilePath);
      }
      END_LOCK_MUTEX
   }
   
   void deregisterLock(const FilePath& lockFilePath)
   {
      LOCK_MUTEX(mutex_)
      {
         registration_.erase(lockFilePath);
      }
      END_LOCK_MUTEX
   }
   
   void refreshLocks()
   {
      LOCK_MUTEX(mutex_)
      {
         BOOST_FOREACH(const FilePath& lockFilePath, registration_)
         {
            lockFilePath.setLastWriteTime();
         }
      }
      END_LOCK_MUTEX
   }
   
   void clearLocks()
   {
      LOCK_MUTEX(mutex_)
      {
         BOOST_FOREACH(const FilePath& lockFilePath, registration_)
         {
            Error error = lockFilePath.removeIfExists();
            if (error)
               LOG_ERROR(error);
         }
         registration_.clear();
      }
      END_LOCK_MUTEX
   }
   
private:
   
   boost::mutex mutex_;
   std::set<FilePath> registration_;
};

LockRegistration& lockRegistration()
{
   static LockRegistration instance;
   return instance;
}

Error writeLockFile(const FilePath& lockFilePath)
{

#ifndef _WIN32

   // generate proxy lockfile
   FilePath proxyPath = lockFilePath.parent().complete(proxyLockFileName());
   
   // since the proxy lockfile should be unique, it should _never_ be possible
   // for a collision to be found. if that does happen, it must be a leftover
   // from a previous process that crashed in this stage
   Error error = proxyPath.removeIfExists();
   if (error)
      LOG_ERROR(error);
   
   // ensure the proxy file is created, and remove it when we're done
   RemoveOnExitScope scope(proxyPath, ERROR_LOCATION);
   error = proxyPath.ensureFile();
   if (error)
   {
      // log the error since it isn't expected and could get swallowed
      // upstream by a caller ignore lock_not_available errors
      LOG_ERROR(error);
      return error;
   }
   
   // attempt to link to the desired location -- ignore return value
   // and just stat our original link after, as that's a more reliable
   // indicator of success on old NFS systems
   ::link(
            proxyPath.absolutePathNative().c_str(),
            lockFilePath.absolutePathNative().c_str());

   struct stat info;
   int errc = ::stat(proxyPath.absolutePathNative().c_str(), &info);
   if (errc)
   {
      // log the error since it isn't expected and could get swallowed
      // upstream by a caller ignore lock_not_available errors
      Error error = systemError(errno, ERROR_LOCATION);
      LOG_ERROR(error);
      return error;
   }
   
   // assume that a failure here is the result of someone else
   // acquiring the lock before we could
   if (info.st_nlink != 2)
      return fileExistsError(ERROR_LOCATION);
   
   return Success();
   
#else

   return systemError(boost::system::errc::function_not_supported, ERROR_LOCATION);

#endif
}

} // end anonymous namespace

struct LinkBasedFileLock::Impl
{
   FilePath lockFilePath;
};

LinkBasedFileLock::LinkBasedFileLock()
   : pImpl_(new Impl())
{
}

LinkBasedFileLock::~LinkBasedFileLock()
{
}

FilePath LinkBasedFileLock::lockFilePath() const
{
   return pImpl_->lockFilePath;
}

bool LinkBasedFileLock::isLocked(const FilePath& lockFilePath) const
{
   if (!lockFilePath.exists())
      return false;
   
   return !isLockFileStale(lockFilePath);
}

Error LinkBasedFileLock::acquire(const FilePath& lockFilePath)
{
   // if the lock file exists...
   if (lockFilePath.exists())
   {
      // ... and it's stale, it's a leftover lock from a previously
      // (crashed?) process. remove it and acquire our own lock
      if (isLockFileStale(lockFilePath))
      {
         // note that multiple processes may attempt to remove this
         // file at the same time, so errors shouldn't be fatal
         Error error = lockFilePath.remove();
         if (error)
            LOG_ERROR(error);
      }
      
      // ... it's not stale -- someone else has the lock, cannot proceed
      else
      {
         return systemError(boost::system::errc::no_lock_available,
                            ERROR_LOCATION);
      }
   }
   
   // ensure the parent directory exists
   Error error = lockFilePath.parent().ensureDirectory();
   if (error)
      return error;

   // write the lock file -- this step _must_ be atomic and so only one
   // competing process should be able to succeed here
   error = writeLockFile(lockFilePath);
   if (error)
   {
      return systemError(boost::system::errc::no_lock_available,
                         error,
                         ERROR_LOCATION);
   }

   // clean any other stale lockfiles in that directory
   cleanStaleLockfiles(lockFilePath.parent());
   
   // register our lock (for refresh)
   pImpl_->lockFilePath = lockFilePath;
   lockRegistration().registerLock(lockFilePath);
   return Success();
}

Error LinkBasedFileLock::release()
{
   const FilePath& lockFilePath = pImpl_->lockFilePath;
   
   Error error = lockFilePath.remove();
   if (error)
      LOG_ERROR(error);
   
   pImpl_->lockFilePath = FilePath();
   lockRegistration().deregisterLock(lockFilePath);
   return error;
}

void LinkBasedFileLock::refresh()
{
   lockRegistration().refreshLocks();
}

void LinkBasedFileLock::cleanUp()
{
   lockRegistration().clearLocks();
}

} // namespace core
} // namespace rstudio
