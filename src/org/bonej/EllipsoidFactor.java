package org.bonej;

/**
 * EllipsoidFactor plugin for ImageJ
 * Copyright 2013 Michael Doube
 * 
 *This program is free software: you can redistribute it and/or modify
 *it under the terms of the GNU General Public License as published by
 *the Free Software Foundation, either version 3 of the License, or
 *(at your option) any later version.
 *
 *This program is distributed in the hope that it will be useful,
 *but WITHOUT ANY WARRANTY; without even the implied warranty of
 *MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *GNU General Public License for more details.
 *
 *You should have received a copy of the GNU General Public License
 *along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.Comparator;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij.gui.GenericDialog;
import ij.macro.Interpreter;
import ij.measure.Calibration;

import org.doube.geometry.EigenCalculator;
import org.doube.geometry.Vectors;
import org.doube.geometry.Ellipsoid;
import org.doube.jama.EigenvalueDecomposition;
import org.doube.skeleton.Skeletonize3D;
import org.doube.util.ImageCheck;
import org.doube.util.ResultInserter;
import org.doube.util.UsageReporter;

/**
 * <p>
 * <b>Plate_Rod</b>
 * </p>
 * <p>
 * ImageJ plugin to describe the local geometry of a binary image in an
 * oblate/prolate spheroid space. Uses Skeletonize3D to generate a 3D skeleton,
 * the points of which are used as centres for star volumes. Local geometry is
 * determined by the ratio between the first and second eigenvalues and first
 * and third eigenvalues of each star volume.
 * </p>
 * 
 * @author Michael Doube
 * 
 */
/*
 * TODO Summarise plateness / rodness, e.g. ratio of sums of middle and biggest
 * Eigenvalues plot ev2/ev1 on 3d skeleton
 */

public class EllipsoidFactor implements PlugIn, Comparator<Ellipsoid> {
	private int nVectors = 1000;

	public void run(String arg) {
		if (!ImageCheck.checkEnvironment())
			return;
		ImagePlus imp = IJ.getImage();
		if (imp == null) {
			IJ.noImage();
			return;
		}
		ImageCheck ic = new ImageCheck();
		if (!ic.isBinary(imp) || !ic.isMultiSlice(imp)) {
			IJ.error("8-bit binary stack required.");
			return;
		}
		Calibration cal = imp.getCalibration();
		double vD = cal.pixelDepth;
		double vH = cal.pixelHeight;
		double vW = cal.pixelWidth;
		String units = cal.getUnits();
		double samplingIncrement = Math.max(vH, Math.max(vW, vD));
		GenericDialog gd = new GenericDialog("Setup");
		gd.addNumericField("Sampling increment", samplingIncrement, 3, 8, units);
		gd.addNumericField("Vectors", nVectors, 0, 8, "");
		gd.addHelp("http://bonej.org/ef");
		gd.showDialog();
		if (!Interpreter.isBatchMode()) {
			samplingIncrement = gd.getNextNumber();
			nVectors = (int) Math.round(gd.getNextNumber());
		}
		if (gd.wasCanceled())
			return;
		double[][] randomVectors = Vectors.randomVectors(nVectors);
		double[][] skeletonPoints = skeletonPoints(imp);
		double[][] localEigenValues = localEigenValues(imp, randomVectors,
				skeletonPoints, samplingIncrement);

		double sumEv1 = 0, sumEv2 = 0, sumEv3 = 0;
		int NaNs = 0;
		for (int l = 0; l < localEigenValues.length; l++) {
			final Double ex = localEigenValues[l][0];
			final Double ey = localEigenValues[l][1];
			final Double ez = localEigenValues[l][2];
			if (ex.equals(Double.NaN) || ey.equals(Double.NaN)
					|| ez.equals(Double.NaN)) {
				NaNs++;
			} else {
				sumEv1 += localEigenValues[l][0];
				sumEv2 += localEigenValues[l][1];
				sumEv3 += localEigenValues[l][2];
			}
		}
		IJ.log(NaNs + " tests hit the sides");

		ResultInserter ri = ResultInserter.getInstance();
		ri.setResultInRow(imp, "ΣeV1", sumEv1);
		ri.setResultInRow(imp, "ΣeV2", sumEv2);
		ri.setResultInRow(imp, "ΣeV3", sumEv3);
		ri.setResultInRow(imp, "ΣeV2/ΣeV1", sumEv2 / sumEv1);
		ri.setResultInRow(imp, "ΣeV3/ΣeV1", sumEv3 / sumEv1);
		ri.updateTable();
		UsageReporter.reportEvent(this).send();
	}

	/* ----------------------------------------------------------------------- */

	private double[][] skeletonPoints(ImagePlus imp) {
		Skeletonize3D sk = new Skeletonize3D();
		ImageStack skeletonStack = sk.getSkeleton(imp).getStack();
		final int d = imp.getStackSize();
		final int h = imp.getHeight();
		final int w = imp.getWidth();
		double vW = imp.getCalibration().pixelWidth;
		double vH = imp.getCalibration().pixelHeight;
		double vD = imp.getCalibration().pixelDepth;
		int count = 0;
		for (int z = 1; z <= d; z++) {
			byte[] slicePixels = (byte[]) skeletonStack.getPixels(z);
			for (int y = 0; y < h; y++) {
				int offset = y * w;
				for (int x = 0; x < w; x++) {
					if (slicePixels[offset + x] < 0) {
						count++;
					}
				}
			}
		}
		IJ.log("Counted " + count + " skeleton points");
		double[][] skeletonPoints = new double[count][3];
		int p = 0;
		for (int z = 0; z < d; z++) {
			byte[] slicePixels = (byte[]) skeletonStack.getPixels(z + 1);
			for (int y = 0; y < h; y++) {
				int offset = y * w;
				for (int x = 0; x < w; x++) {
					if (slicePixels[offset + x] < 0) {
						skeletonPoints[p][0] = (double) x * vW;
						skeletonPoints[p][1] = (double) y * vH;
						skeletonPoints[p][2] = (double) z * vD;
						p++;
					}
				}
			}
		}
		return skeletonPoints;
	}

	private double[][] localEigenValues(ImagePlus imp,
			double[][] randomVectors, double[][] skeletonPoints,
			double samplingIncrement) {
		ImageStack stack = imp.getImageStack();
		double[][] localEigenValues = new double[skeletonPoints.length][3];
		Calibration cal = imp.getCalibration();
		final double vD = cal.pixelDepth;
		final double vH = cal.pixelHeight;
		final double vW = cal.pixelWidth;
		final int w = stack.getWidth();
		final int h = stack.getHeight();
		final int d = stack.getSize();
		final int nP = skeletonPoints.length;
		final int nV = randomVectors.length;
		// instantiate ImageProcessors to access the stack slices
		ImageProcessor[] ips = new ImageProcessor[d];
		for (int s = 0; s < d; s++) {
			ips[s] = stack.getProcessor(s + 1);
		}

		for (int p = 0; p < nP; p++) {
			IJ.showStatus("Calculating local eigenvalues");
			IJ.showProgress(p, nP);
			final double sX = skeletonPoints[p][0];
			final double sY = skeletonPoints[p][1];
			final double sZ = skeletonPoints[p][2];
			boolean hitSide = false;
			double[][] localStar = new double[nV][3];
			for (int v = 0; v < nV; v++) {
				final double vecX = randomVectors[v][0];
				final double vecY = randomVectors[v][1];
				final double vecZ = randomVectors[v][2];
				if (hitSide)
					break;
				int pixelValue = 255;
				double vecL = 0;
				while (pixelValue == 255 && !hitSide) {
					final int tX = (int) Math.floor((sX + vecX * vecL) / vW);
					final int tY = (int) Math.floor((sY + vecY * vecL) / vH);
					final int tZ = (int) Math.floor((sZ + vecZ * vecL) / vD);
					if (tX < 0 || tX >= w || tY < 0 || tY >= h || tZ < 0
							|| tZ >= d) {
						hitSide = true;
						break;
					} else {
						pixelValue = ips[tZ].get(tX, tY);
						vecL += samplingIncrement;
					}
				}
				localStar[v][0] = vecL * vecX;
				localStar[v][1] = vecL * vecY;
				localStar[v][2] = vecL * vecZ;
			}
			if (!hitSide) {
				EigenvalueDecomposition E = EigenCalculator.principalComponents(localStar);
				localEigenValues[p][0] = E.getD().get(2, 2);
				localEigenValues[p][1] = E.getD().get(1, 1);
				localEigenValues[p][2] = E.getD().get(0, 0);
			} else {
				localEigenValues[p][0] = Double.NaN;
				localEigenValues[p][1] = Double.NaN;
				localEigenValues[p][2] = Double.NaN;
			}
		}
		return localEigenValues;
	}

	/**
	 * Compare Ellipsoids by volume
	 */
	public int compare(Ellipsoid o1, Ellipsoid o2) {
		return Double.compare(o1.getVolume(), o2.getVolume());
	}
}
