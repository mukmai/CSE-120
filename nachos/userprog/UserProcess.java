package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;
import java.util.*;

import java.io.EOFException;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
                UserKernel.mutex2.acquire();
                process_id = UserKernel.id_generator;
                UserKernel.id_generator++;
                UserKernel.n_of_process++;
                UserKernel.mutex2.release();
                fileTable[0] = UserKernel.console.openForReading();
                fileTable[1] = UserKernel.console.openForWriting();
                for(int i = 2; i < 16; i++){
                  fileTable[i] = null;
                }
                used_pages = new LinkedList<Integer>();
                children_running = new HashMap<Integer, UserProcess>();
                children_pid = new HashSet<Integer>();
                children_stat = new HashMap<Integer, Integer>();
                lock = new Lock();
                CV = new Condition(lock);
                normal = true;
                parent = null;
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
	        String name = Machine.getProcessClassName ();

		// If Lib.constructObject is used, it quickly runs out
		// of file descriptors and throws an exception in
		// createClassLoader.  Hack around it by hard-coding
		// creating new processes of the appropriate type.

		if (name.equals ("nachos.userprog.UserProcess")) {
		    return new UserProcess ();
		} else if (name.equals ("nachos.vm.VMProcess")) {
		    return new VMProcess ();
		} else {
		    return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
		}
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		new UThread(this).setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr the starting virtual address of the null-terminated string.
	 * @param maxLength the maximum number of characters in the string, not
	 * including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 * found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to the
	 * array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

                if(vaddr < 0){
                  return 0;
                }
                int left = length;
                int amount = 0;
                int cur_offset = offset;
                int total_read = 0;
                int paddr = -1;
                int paddr_offset = Processor.offsetFromAddress(vaddr);
                int vpn = Processor.pageFromAddress(vaddr);
                for(int i = 0; i < pageTable.length; i++){
                  if(pageTable[i].vpn == vpn && pageTable[i].valid){
                    paddr = pageTable[i].ppn * pageSize + paddr_offset;
                    break;
                  }
                }
		// for now, just assume that virtual addresses equal physical addresses
		if (paddr < 0 || paddr >= memory.length)
			return 0;

                amount = Math.min(left, (pageSize - paddr_offset));
                System.arraycopy(memory, paddr, data, offset, amount);
                total_read += amount;
                cur_offset += amount;
                left -= amount;
                boolean notFound = true;
                while(left > 0){
                  vpn++;
                  for(int i = 0; i < pageTable.length; i++){
                    if(pageTable[i].vpn == vpn && pageTable[i].valid){
                      paddr = pageTable[i].ppn * pageSize;
                      notFound = false;
                      break;
                    }
                  }
                  
                  if(notFound || paddr < 0 || paddr >= memory.length){
                    return total_read;
                  }
                  notFound = true;
                  amount = Math.min(left, pageSize);
                  System.arraycopy(memory, paddr, data, cur_offset, amount);
                  total_read += amount;
                  cur_offset += amount;
                  left -= amount;
                }

		return total_read;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 * memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

                if(vaddr < 0){
                  return 0;
                }
                int left = length;
                int amount = 0;
                int cur_offset = offset;
                int total_write = 0;
                int paddr = -1;
                int paddr_offset = Processor.offsetFromAddress(vaddr);
                int vpn = Processor.pageFromAddress(vaddr);
                for(int i = 0; i < pageTable.length; i++){
                  if(pageTable[i].vpn == vpn && pageTable[i].valid && pageTable[i].readOnly == false){
                    paddr = pageTable[i].ppn * pageSize + paddr_offset;
                  }
                }

		// for now, just assume that virtual addresses equal physical addresses
		if (paddr < 0 || paddr >= memory.length)
			return 0;

                amount = Math.min(left, (pageSize - paddr_offset));
                System.arraycopy(data, offset, memory, paddr, amount);
                total_write += amount;
                cur_offset += amount;
                left -= amount;
                boolean notFound = true;
                while(left > 0){
                  vpn++;
                  for(int i = 0; i < pageTable.length; i++){
                    if(pageTable[i].vpn == vpn && pageTable[i].valid && pageTable[i].readOnly == false){
                      paddr = pageTable[i].ppn * pageSize;
                      notFound = false;
                      break;
                    }
                  }
                  
                  if(notFound || paddr < 0 || paddr >= memory.length){
                    return total_write;
                  }
                  notFound = true;
                  amount = Math.min(left, pageSize);
                  System.arraycopy(data, cur_offset, memory, paddr, amount);
                  total_write += amount;
                  cur_offset += amount;
                  left -= amount;
                }

		return total_write;

		
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}
		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
                UserKernel.mutex.acquire();
		if (numPages > UserKernel.free_pages.size()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}
                int count = 0;
                int track = 0;
                pageTable = new TranslationEntry[numPages];
                for(int i = 0; i < numPages; i++){
                  pageTable[i] = new TranslationEntry(i, i, true, false, false, false);
                }
		// load sections
	        System.out.println(  "  Process[" + process_id + "] loadSection");
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");
                        
			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				// for now, just assume virtual addresses=physical addresses
				int ppn = UserKernel.free_pages.removeLast();
                                track = vpn;
                                used_pages.add(ppn);
                                System.out.println("  " +ppn + "assigned to Process[" + process_id + "]");
				section.loadPage(i, ppn); // this load to PMem?
                                if(section.isReadOnly()){
                                  pageTable[count] = new TranslationEntry(vpn, ppn, true, true, false, false);
                                }
                                else{    
                                  pageTable[count] = new TranslationEntry(vpn, ppn, true, false, false, false);
                                }
                                count++;

			}
		}   
                track++;
                int pages_left = numPages - count;
                for(int i = 0; i < pages_left; i++){
                  int ppn = UserKernel.free_pages.removeLast();
                  used_pages.add(ppn);
                  System.out.println("  " + ppn + "assigned to Process[" + process_id + "]");
                  pageTable[count + i] = new TranslationEntry(track + i, ppn, true, false, false, false);
                } 
                UserKernel.mutex.release();
		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
          UserKernel.mutex.acquire();
          System.out.println("  Process[" + process_id + "] unloadSection");
          while(!used_pages.isEmpty()){
            UserKernel.free_pages.add(used_pages.removeLast());
          }
          System.out.println("  used page.size: " + used_pages.size());

          System.out.println("  free page.size: " + UserKernel.free_pages.size());
          UserKernel.mutex.release();
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {
                if(process_id != 0){
                  return 0;
                }
		Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}

	/**
	 * Handle the exit() system call.
	 */
	private int handleExit(int status) {
		Machine.autoGrader().finishingCurrentProcess(status);





                System.out.println("Proccess["+ process_id +"]Exit status(handleExit) " + status);
                System.out.println("  " + normal);
                //System.out.println("hao");



                for(int i = 0; i < 16; i++){
                  if(fileTable[i] != null){
                    fileTable[i].close();
                    fileTable[i] = null;
                  }
                }
                unloadSections();
                coff.close();
                Set<Integer> keys = children_running.keySet();

                for(Integer id: keys){
                  children_running.get(id).parent = null;
                }// terminated children?????????

   // ---------------------------------- save the status to parent ????????>???what ?? status set to 0?????? status return to the parent
                if(parent != null){
                  parent.children_running.remove(this.process_id);
                  if(normal){ 


                    System.out.println("-Process[" + process_id + "] Exit Status(removing child): " + status);


                    parent.children_stat.put(this.process_id, status);
                  }
                  lock.acquire();  
                  CV.wake();
                  lock.release();
                }             
   // ---------------------------------
                UserKernel.mutex2.acquire();
                if(UserKernel.n_of_process == 1){
                  UserKernel.n_of_process--;
                  UserKernel.mutex2.release();
                  Kernel.kernel.terminate();
                  KThread.currentThread().finish();
                }
                else{
                  UserKernel.n_of_process--;
                  UserKernel.mutex2.release();
                  KThread.currentThread().finish();
                }
                

		return 0;
	}

      
        private int handleExec(int file, int argc, int argv){
          String filename = readVirtualMemoryString(file, 256);
          if(argc < 0 || filename == null || filename.length() <= 5){
            return -1;
          }
        
          String extension = filename.substring(filename.length() - 5);

          if(!extension.equals(".coff")){
            return -1;
          }

          String[] arguments = new String[argc];
          for(int i = 0; i < argc; i++){  
            byte[] pointer = new byte[4];
            readVirtualMemory(argv + i * 4, pointer);
            int vaddr = Lib.bytesToInt(pointer, 0);
            arguments[i] = readVirtualMemoryString(vaddr, 256);
//System.out.println((String)arguments[i]);
            if(arguments[i] == null){
              return -1;
            }
          }

       
          UserProcess child_process = newUserProcess(); // no process if failed????
          boolean success = child_process.execute(filename, arguments);
     

          if(success){
            child_process.parent = this;
            this.children_running.put(child_process.process_id, child_process);
            this.children_pid.add(child_process.process_id);
            return child_process.process_id;
          }
          else{
            UserKernel.mutex2.acquire();
            UserKernel.n_of_process--;
            UserKernel.mutex2.release();
            return -1;
          }
        }
 

        private int handleJoin(int processID, int status){
          UserProcess child = null;

          if(!children_pid.contains(processID)){
            return -1;
          }
             
          if(!children_running.containsKey(processID)){
 //System.out.println("aaaaaaaaaa");
            Integer toRemove = new Integer(processID);
            children_pid.remove(toRemove);
            if(children_stat.containsKey(processID)){
              int status_to_save = children_stat.get(processID);
              byte[] buffer = Lib.bytesFromInt(status_to_save);
              writeVirtualMemory(status, buffer);
              return 1;
            } // sha bi de wen ti if already exit?? what to return???
            else{
              return 0;
            }
          }
 //System.out.println("bbbbbbb");
        
          children_running.get(processID).lock.acquire();
          child = children_running.get(processID);
          children_running.get(processID).CV.sleep();
          //children_running.get(processID).lock.release();
          child.lock.release();

          Integer toRemove = new Integer(processID);
          children_pid.remove(toRemove);
          if(children_stat.containsKey(processID)){
            int status_to_save = children_stat.get(processID);
            byte[] buffer = Lib.bytesFromInt(status_to_save);
            writeVirtualMemory(status, buffer);
            return 1;
          } 
          else{
            return 0;
          }             
        }


        private int handleCreate(int name){
          String filename = readVirtualMemoryString(name, 256);
          if(filename == null){
            return -1;
          }

System.out.println(" File to be created:" + filename);


          OpenFile f = ThreadedKernel.fileSystem.open(filename, false);
          if(f != null){
            int fd = -1;
            for(int i = 0; i < 16; i++){
              if(fileTable[i] == null){
                fd = i;
                fileTable[i] = f;
                break;
              }
            }
            return fd;
          }
          else{
            f = ThreadedKernel.fileSystem.open(filename, true);
            if(f != null){
              int fd = -1;
              for(int i = 0; i < 16; i++){
                if(fileTable[i] == null){
                  fd = i;
                  fileTable[i] = f;
                  break;
                }
              }
              return fd;
            }
            else{
              return -1;
            }
          }
        }
 
        private int handleOpen(int name){
          String filename = readVirtualMemoryString(name, 256);
          if(filename == null){
            return -1;
          }
          OpenFile f = ThreadedKernel.fileSystem.open(filename, false);
          if(f != null){
            int fd = -1;
            for(int i = 0; i < 16; i++){
              if(fileTable[i] == null){
                fd = i;
                fileTable[i] = f;
                break;
              }
            }
            return fd;
          }
          else{
            return -1;
          }
        }
 
        private int handleRead(int fd, int buffer, int count){
          if(fd < 0 || fd > 15 || buffer < 0 || fileTable[fd] == null || count < 0){
            return -1;
          } 
          byte[] local_buffer = new byte[1024];
          int counter = 0;
          while(counter < count){
            if((count - counter) >= 1024){
              int readByte = 0;

              readByte = fileTable[fd].read(local_buffer, 0, 1024);
              
              if(readByte == -1){
                return -1;
              }
              else{
                int writeByte = 0;
                if(readByte < 1024){
                  byte[] offset_buffer = new byte[readByte];
                  for(int i = 0; i < readByte; i++){
                    offset_buffer[i] = local_buffer[i];
                  }
                  writeByte = writeVirtualMemory(buffer + counter, offset_buffer);
                  // assert out of memory error
                  if(writeByte < readByte){
                    return -1;
                  }
                  // --------------------
                  counter += writeByte;
                  return counter;
                }
                writeByte = writeVirtualMemory(buffer + counter, local_buffer); // out of memeory???
                counter += writeByte;
                // assert out of memory error
                if(writeByte < 1024){
                  return -1;
                }
                // ------------------------
              }
            }

            else{
              int readByte = 0;
              int writeByte = 0;
              readByte = fileTable[fd].read(local_buffer, 0, (count - counter));
              
              if(readByte == -1){
                return -1;
              }
              else{
                byte[] offset_buffer = new byte[readByte];
                for(int i = 0; i < offset_buffer.length; i++){
                  offset_buffer[i] = local_buffer[i];
                }
                writeByte = writeVirtualMemory(buffer + counter, offset_buffer);
                // assert out of memory error
                if(writeByte < readByte){
                  return -1;
                }
                // -------------------------------------
                counter += writeByte;

                return counter;
              }
            }
          }
          return counter; // if counter == count
        }

        private int handleWrite(int fd, int buffer, int count){ 
           if(fd < 0 || fd > 15 || buffer < 0 || fileTable[fd] == null || count < 0){
             return -1;
           }
           byte [] local_buffer = new byte[1024];
           int counter = 0;
           while(counter < count){
             if((count - counter) >= 1024){
               int readByte = readVirtualMemory(buffer + counter, local_buffer, 0, 1024);
               if(readByte < 1024){
                 return -1; // out of memory?
               }

               int writeByte = 0;
               writeByte = fileTable[fd].write(local_buffer, 0, readByte);
               if(writeByte == -1){
                 return -1; // disk full or stream terminate
               }
               counter += writeByte;
             }
             else{
               int readByte = readVirtualMemory(buffer + counter, local_buffer, 0, count - counter);
               if(readByte < (count - counter)){
                 return -1;
               }
               int writeByte = 0;
               writeByte = fileTable[fd].write(local_buffer, 0, readByte);
               // if not correctly write do not need to check less than readByte because it must return readByte 
               if(writeByte == -1){
                 return -1;
               }
               counter += writeByte;
             }
           } 
       
           return counter;
        }

        private int handleClose(int fileDescriptor){
          if(fileDescriptor < 0 || fileDescriptor > 15 || fileTable[fileDescriptor] == null){
            return -1;
          }
          fileTable[fileDescriptor].close();  // catch exception??????? file must be opened????
          fileTable[fileDescriptor] = null;
          return 0;
        }

        private int handleUnlink(int name){
          String filename = readVirtualMemoryString(name, 256);
          if(filename == null){
            return -1;
          }

          //however, creat() and open() will not be able to
          //return new file descriptors for the file until it is deleted.
          if(ThreadedKernel.fileSystem.remove(filename)){
            return 0;
          }
          else{
            return -1;
          }
        }


	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall the syscall number.
	 * @param a0 the first syscall argument.
	 * @param a1 the second syscall argument.
	 * @param a2 the third syscall argument.
	 * @param a3 the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
		case syscallHalt:
			return handleHalt();
		case syscallExit:
			return handleExit(a0);
		case syscallExec:
			return handleExec(a0, a1, a2);
		case syscallJoin:
			return handleJoin(a0, a1);
                case syscallCreate:
			return handleCreate(a0);
                case syscallOpen:
			return handleOpen(a0);
                case syscallRead:
			return handleRead(a0, a1, a2);
                case syscallWrite:
			return handleWrite(a0, a1, a2);
                case syscallClose:
			return handleClose(a0);
                case syscallUnlink:
			return handleUnlink(a0);
		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
	}



	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3));
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;

		default:
                        normal = false;
                        handleExit(-1);
			Lib.debug(dbgProcess, "Unexpected exception: "
					+ Processor.exceptionNames[cause]);
System.out.println(Processor.exceptionNames[cause]);
			Lib.assertNotReached("Unexpected exception");
		}
	}

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	private int initialPC, initialSP;

	private int argc, argv;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

        private OpenFile[] fileTable = new OpenFile[16];

        public LinkedList<Integer> used_pages;

        public int process_id;

        private HashMap<Integer, UserProcess> children_running;

        private HashSet<Integer> children_pid;

        private HashMap<Integer, Integer> children_stat;

        private UserProcess parent;

        private boolean normal;

        //public Lock 
 
        public Condition CV;

        public Lock lock;
}

