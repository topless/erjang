/**
 * This file is part of Erjang - A JVM-based Erlang VM
 *
 * Copyright (c) 2009 by Trifork
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
 **/

package erjang;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import erjang.beam.Compiler;

import kilim.Pausable;

/**
 * 
 */
public class EModuleManager {

	static Logger log = Logger.getLogger(EModuleManager.class.getName());
	
	static private Map<EAtom, ModuleInfo> infos = new ConcurrentHashMap<EAtom, ModuleInfo>();

	static FunctionInfo undefined_function = null;

	static {
		FunID uf = new FunID("error_handler", "undefined_function", 3);
		undefined_function = get_module_info(uf.module).get_function_info(uf);
	}

	static class FunctionInfo {
		private final FunID fun;

		/**
		 * @param fun
		 */
		public FunctionInfo(FunID fun) {
			this.fun = fun;
		}

		EModule defining_module;
		EFun resolved_value;
		Collection<FunctionBinder> resolve_points = new HashSet<FunctionBinder>();
		private EFun error_handler;

	        private ClassLoader getModuleClassLoader() {
		    if (defining_module != null) {
			return defining_module.getModuleClassLoader();
		    } else {
			return new EModuleClassLoader(null);
		    }
	        }

		/**
		 * @param ref
		 * @throws IllegalAccessException
		 * @throws IllegalArgumentException
		 */
		synchronized void add_import(final FunctionBinder ref) throws Exception {
			resolve_points.add(ref);
			if (resolved_value != null) {
				// System.out.println("binding "+fun+" "+resolved_value+" -> "+ref);
				ref.bind(resolved_value);
			} else {
				EFun h = getFunErrorHandler();
				ref.bind(h);
			}
		}

		private EFun getFunction() {
			if (resolved_value != null) {
				return resolved_value;
			} else {
				return getFunErrorHandler();
			}
		}

		private EFun getFunErrorHandler() {
			if (error_handler != null) {
				return error_handler;
			}
			
			error_handler = makeErrorHandler();
			return error_handler;
		}

		private EFun makeErrorHandler() {
			return EFun.get_fun_with_handler(fun.arity,
					new EFunHandler() {
					public EObject invoke(EProc proc, EObject[] args)
								throws Pausable {
							
							/** Get reference to error_handler:undefined_function/3 */
							EFun uf = undefined_function.resolved_value;

							/** this is just some debugging info to help understand downstream errors */
							if (get_module_info(fun.module).is_loaded()) {
								log.log(Level.INFO, "MISSING "+fun);
							} else {
								log.log(Level.FINER, "resolving"+fun);
							}
								
							if (uf == null) {
								if (!module_loaded(fun.module)) {
									try {
										EModuleLoader.find_and_load_module(fun.module.getName());
									} catch (IOException e) {
										// we don't report this separately; it ends up causing an undefined below...
									}
								}
								
								if (resolved_value != null) {
									return resolved_value.invoke(proc, args);
								}
								
								/** this is just some debugging info to help understand downstream errors */
								log.log(Level.INFO, "failed to load "+fun+" (error_handler:undefined_function/3 not found)");
								
								throw new ErlangUndefined(fun.module,
										fun.function, fun.arity);
							} else {
								ESeq arg_list = ESeq.fromArray(args);
								ESeq ufa = ESeq.fromArray(new EObject[] {
										fun.module, fun.function, arg_list });
								return uf.apply(proc, ufa);
							}
						}
					},
							 getModuleClassLoader());
		}

		/**
		 * @param fun2
		 * @param value
		 */
		synchronized void add_export(EModule definer, FunID fun2, EFun value)
				throws Exception {
			this.resolved_value = value;
			this.defining_module = definer;

			for (FunctionBinder f : resolve_points) {
				// System.out.println("binding " + fun2 + " " + value + " -> " + f);
				f.bind(value);
			}
		}

		/**
		 * @return
		 * 
		 */
		public EFun resolve() {
			return getFunction();
		}

		/**
		 * @return
		 */
		public boolean exported() {
			return resolved_value != null;
		}
	}

	static class ModuleInfo {

		private EModule resident;

		/**
		 * @param module
		 */
		public ModuleInfo(EAtom module) {
		}

		Map<FunID, FunctionInfo> binding_points = new ConcurrentHashMap<FunID, FunctionInfo>();

		/**
		 * @param fun
		 * @param ref
		 * @return
		 * @throws Exception
		 */
		public void add_import(FunID fun, FunctionBinder ref) throws Exception {
			FunctionInfo info = get_function_info(fun);
			info.add_import(ref);
		}

		private synchronized FunctionInfo get_function_info(FunID fun) {
			FunctionInfo info = binding_points.get(fun);
			if (info == null) {
				binding_points.put(fun, info = new FunctionInfo(fun));
			}
			return info;
		}

		/**
		 * @param fun
		 * @param value
		 * @throws Exception
		 */
		public void add_export(EModule definer, FunID fun, EFun value)
				throws Exception {
			get_function_info(fun).add_export(definer, fun, value);
		}

		/**
		 * @param eModule
		 */
		public void setModule(EModule eModule) {
			this.resident = eModule;
		}

		/**
		 * @param start
		 * @return
		 */
		public EFun resolve(FunID fun) {
			return get_function_info(fun).resolve();
		}

		/**
		 * @param fun
		 * @return
		 */
		public boolean exports(FunID fun) {
			return get_function_info(fun).exported();
		}

		/**
		 * @return
		 */
		public boolean is_loaded() {
			return resident != null;
		}

		/**
		 * @return
		 */
		public synchronized ESeq get_attributes() {
			ESeq res;
			
			if (resident == null)
				res = ERT.NIL;
			else
				res = resident.attributes();
			
			return res;
		}

		/**
		 * 
		 */
		public void warn_about_unresolved() {
			if (resident != null) {
				for (FunctionInfo fi : binding_points.values()) {
					if (fi.resolved_value == null) {
						log.log(Level.INFO, "unresolved after load: "+fi.fun);
					}
				}
			}
		}

		public ESeq get_exports() {
			ESeq rep = ERT.NIL;
			
			for (FunctionInfo fi : binding_points.values()) {
				if (fi.exported()) {
					rep = rep.cons(new ETuple2(fi.fun.function, ERT.box(fi.fun.arity)));
				}
			}

			return rep;
		}

	}

	public static void add_import(FunID fun, FunctionBinder ref) throws Exception {
		get_module_info(fun.module).add_import(fun, ref);
	}

	private static ModuleInfo get_module_info(EAtom module) {
		ModuleInfo mi;
		synchronized (infos) {
			mi = infos.get(module);
			if (mi == null) {
				infos.put(module, mi = new ModuleInfo(module));
			}
		}
		return mi;
	}

	public static void add_export(EModule mod, FunID fun, EFun value) throws Exception {
		get_module_info(fun.module).add_export(mod, fun, value);
	}

	// static private Map<EAtom, EModule> modules = new HashMap<EAtom,
	// EModule>();

	static void setup_module(EModule mod_inst) throws Error {

		ModuleInfo module_info = get_module_info(EAtom.intern(mod_inst.module_name()));
		module_info.setModule(mod_inst);

		try {
			mod_inst.registerImportsAndExports();
		} catch (Exception e) {
			throw new Error(e);
		}

		module_info.warn_about_unresolved();
	}

	/**
	 * @param start
	 * @return
	 */
	public static EFun resolve(FunID start) {
		return get_module_info(start.module).resolve(start);
	}

	/**
	 * @param m
	 * @param f
	 * @param value
	 * @return
	 */
	public static boolean function_exported(EAtom m, EAtom f, int a) {
		FunID fun = new FunID(m, f, a);
		return get_module_info(m).exports(fun);
	}

	/**
	 * @param m
	 * @return
	 */
	public static boolean module_loaded(EAtom m) {
		ModuleInfo mi = get_module_info(m);
		return mi.is_loaded();
	}

	/**
	 * @param mod
	 * @return
	 */
	public static ESeq get_attributes(EAtom mod) {
		ModuleInfo mi = get_module_info(mod);
		return mi.get_attributes();
	}

	/**
	 * @param mod
	 * @return
	 */
	public static ESeq get_exports(EAtom mod) {
		ModuleInfo mi = get_module_info(mod);
		return mi.get_exports();
	}

	public static abstract class FunctionBinder {
		public abstract void bind(EFun value) throws Exception;
		public abstract FunID getFunID();
	}

}
