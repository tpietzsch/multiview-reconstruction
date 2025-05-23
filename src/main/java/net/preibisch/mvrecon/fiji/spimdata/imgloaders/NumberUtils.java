/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2025 Multiview Reconstruction developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
///////////////////////////////////////////////////////////////////////////////
//FILE:          NumberUtils.java
//PROJECT:       Micro-Manager
//SUBSYSTEM:     mmstudio
//-----------------------------------------------------------------------------
//
// AUTHOR:       Nico Stuurman, nico@cmp.ucsf.edu, March 21, 2009
//
// COPYRIGHT:    University of California, San Francisco, 2009
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
//
//
package net.preibisch.mvrecon.fiji.spimdata.imgloaders;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;

public class NumberUtils {
	private static final NumberFormat format_;
	private static final DecimalFormat coreDoubleFormat_;
	private static final DecimalFormat coreIntegerFormat_;

	static {
		// The display is supposed to use local formating (e.g., switch commas with periods in Locale.GERMANY).
		format_ = NumberFormat.getInstance();
		format_.setRoundingMode(RoundingMode.HALF_UP);
      format_.setMaximumFractionDigits(4);

		// The core always uses four decimal places in its double strings, and a dot for the decimal separator.
		// This is equivalent to the US locale settings.
		coreDoubleFormat_ = (DecimalFormat) DecimalFormat.getInstance(Locale.US);
		coreDoubleFormat_.setRoundingMode(RoundingMode.HALF_UP);
		coreDoubleFormat_.applyPattern("0.0000"); 
		
		coreIntegerFormat_ = (DecimalFormat) DecimalFormat.getInstance(Locale.US);
		coreIntegerFormat_.applyPattern("0");
	}

	// Display string methods
	
	public static String intToDisplayString(int number) {
		return format_.format(number);
	}
   
   public static String longToDisplayString(long number) {
		return format_.format(number);
	}

	public static String doubleToDisplayString(double number) {
		return format_.format(number);
	}

	public static int displayStringToInt(Object numberString) throws ParseException {
		return format_.parse((String) numberString).intValue();
	}

   public static long displayStringToLong(Object numberString) throws ParseException {
		return format_.parse((String) numberString).longValue();
	}
   
	public static double displayStringToDouble(Object numberString) throws ParseException {
		return format_.parse((String) numberString).doubleValue();
	}

	
    // Core string methods
	
	public static String intToCoreString(long number) {
		return coreIntegerFormat_.format(number);
	}

   public static String longToCoreString(long number) {
      return coreIntegerFormat_.format(number);
   }

	public static String doubleToCoreString(double number) {
		return coreDoubleFormat_.format(number);
	}

	public static int coreStringToInt(Object numberString) throws ParseException {
		return coreIntegerFormat_.parse((String) numberString).intValue();
	}

   public static long coreStringToLong(Object numberString) throws ParseException {
		return coreIntegerFormat_.parse((String) numberString).longValue();
	}

	public static double coreStringToDouble(Object numberString) throws ParseException {
		return coreDoubleFormat_.parse((String) numberString).doubleValue();
	}

	
	// Conversion between display and core strings.
	
	public static String doubleStringDisplayToCore(Object numberDouble) throws ParseException {
		return doubleToCoreString(displayStringToDouble(numberDouble));
	}

	public static String doubleStringCoreToDisplay(Object numberDouble) throws ParseException {
		return doubleToDisplayString(coreStringToDouble(numberDouble));
	}

	public static String intStringDisplayToCore(Object numberInt) throws ParseException {
		return intToCoreString(displayStringToInt(numberInt));
	}

	public static String intStringCoreToDisplay(Object numberInt) throws ParseException {
		return intToDisplayString(coreStringToInt(numberInt));
	}



}
