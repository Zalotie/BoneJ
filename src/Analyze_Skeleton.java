import java.util.ArrayList;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.plugin.filter.PlugInFilter;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

/**
 * AnalyzeSkeleton_ plugin for ImageJ(C).
 * Copyright (C) 2008,2009 Ignacio Arganda-Carreras 
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 */

/**
 * <p>
 * This class is a plugin for the ImageJ interface for analyzing
 * 2D/3D skeleton images.
 * </p>
 * @see
 * <p>For more information, visit 
 * <a href="http://imagejdocu.tudor.lu/doku.php?id=plugin:analysis:analyzeskeleton:start">the AnalyzeSkeleton_ homepage</a>.</p>
 *
 *
 * @version 1.0 04/07/2009
 * @author Ignacio Arganda-Carreras <ignacio.arganda@uam.es>
 *
 */
public class Analyze_Skeleton implements PlugInFilter
{
    /** end point flag */
    public static byte END_POINT = 30;
    /** junction flag */
    public static byte JUNCTION = 70;
    /** slab flag */
    public static byte SLAB = 127;

    /** working image plus */
    private ImagePlus imRef;

    /** working image width */
    private int width = 0;
    /** working image height */
    private int height = 0;
    /** working image depth */
    private int depth = 0;
    /**working voxel width */
    private double vW = 1;
    /**working voxel height */
    private double vH = 1;
    /**working voxel depth */
    private double vD = 1;

    /** working image stack*/
    private ImageStack inputImage = null;

    /** visit flags */
    private boolean [][][] visited = null;

    // Measures
    /** total number of end points voxels */
    private int totalNumberOfEndPoints = 0;
    /** total number of junctions voxels */
    private int totalNumberOfJunctionVoxels = 0;
    /** total number of slab voxels */
    private int totalNumberOfSlabs = 0;	

    // Tree fields
    /** number of branches for every specific tree */
    private int[] numberOfBranches = null;
    /** number of end points voxels of every tree */
    private int[] numberOfEndPoints = null;
    /** number of junctions voxels of every tree*/
    private int[] numberOfJunctionVoxels = null;
    /** number of slab voxels of every specific tree */
    private int[] numberOfSlabs = null;	
    /** number of junctions of every specific tree*/
    private int[] numberOfJunctions = null;
    /** number of triple points in every tree */
    private int[] numberOfTriplePoints = null;
    /** list of end points in every tree */
    private ArrayList <int[]> endPointsTree [] = null;
    /** list of junction voxels in every tree */
    private ArrayList <int[]> junctionVoxelTree [] = null;


    /** average branch length */
    private double[] averageBranchLength = null;

    /** maximum branch length */
    private double[] maximumBranchLength = null;

    /** list of end point coordinates */
    private ArrayList <int[]> listOfEndPoints = new ArrayList<int[]>();
    /** list of junction coordinates */
    private ArrayList <int[]> listOfJunctionVoxels = new ArrayList<int[]>();
    /** list of groups of junction voxels that belong to the same tree junction (in every tree) */
    private ArrayList < ArrayList <int[]> > listOfSingleJunctions[] = null;

    /** stack image containing the corresponding skeleton tags (end point, junction or slab) */
    private ImageStack taggedImage = null;

    /** auxiliary temporary point */
    private int[] auxPoint = null;
    /** largest branch coordinates initial point */
    private int[][] initialPoint = null;
    /** largest branch coordinates final point */
    private int[][] finalPoint = null;

    /** number of trees (skeletons) in the image */
    private int numOfTrees = 0;


    /* -----------------------------------------------------------------------*/
    /**
     * This method is called once when the filter is loaded.
     * 
     * @param arg argument specified for this plugin
     * @param imp currently active image
     * @return flag word that specifies the filters capabilities
     */
    public int setup(String arg, ImagePlus imp) 
    {
	this.imRef = imp;
	this.vW = this.imRef.getCalibration().pixelWidth;
	this.vH = this.imRef.getCalibration().pixelHeight;
	this.vD = this.imRef.getCalibration().pixelDepth;

	if (arg.equals("about")) 
	{
	    showAbout();
	    return DONE;
	}

	return DOES_8G; 
    } /* end setup */

    /* -----------------------------------------------------------------------*/
    /**
     * Process the image: tag skeleton and show results.
     * 
     * @see ij.plugin.filter.PlugInFilter#run(ij.process.ImageProcessor)
     */
    public void run(ImageProcessor ip) 
    {
	this.width = this.imRef.getWidth();
	this.height = this.imRef.getHeight();
	this.depth = this.imRef.getStackSize();
	this.inputImage = this.imRef.getStack();

	// initialize visit flags
	this.visited = new boolean[this.width][this.height][this.depth];

	// Prepare data: classify voxels and tag them.
	ImageStack stack1 = tagImage(this.inputImage);

	//remove end branches
	IJ.log("Pruning...");
	this.taggedImage = pruneEndBranches(stack1);
	this.totalNumberOfEndPoints = this.listOfEndPoints.size();

	// Show tags image.
	ImagePlus tagIP = new ImagePlus("Tagged skeleton", taggedImage);
	tagIP.show();

	// Set same calibration as the input image
	tagIP.setCalibration(this.imRef.getCalibration());

	// We apply the Fire LUT and reset the min and max to be between 0-255.
	IJ.run("Fire");

	//IJ.resetMinAndMax();
	tagIP.resetDisplayRange();
	tagIP.updateAndDraw();
	
	//now we hit trouble...
	// Mark trees
	IJ.log("Marking trees");
	ImageStack treeIS = markTrees(taggedImage);

	// Ask memory for every tree
	this.numberOfBranches = new int[this.numOfTrees];
	this.numberOfEndPoints = new int[this.numOfTrees];
	this.numberOfJunctionVoxels = new int[this.numOfTrees];
	this.numberOfJunctions = new int[this.numOfTrees];
	this.numberOfSlabs = new int[this.numOfTrees];
	this.numberOfTriplePoints = new int[this.numOfTrees];
	this.averageBranchLength = new double[this.numOfTrees];
	this.maximumBranchLength = new double[this.numOfTrees];
	this.initialPoint = new int[this.numOfTrees][];
	this.finalPoint = new int[this.numOfTrees][];
	this.endPointsTree = new ArrayList[this.numOfTrees];		
	this.junctionVoxelTree = new ArrayList[this.numOfTrees];
	this.listOfSingleJunctions = new ArrayList[this.numOfTrees];
	for(int i = 0; i < this.numOfTrees; i++)
	{
	    this.endPointsTree[i] = new ArrayList <int[]>();
	    this.junctionVoxelTree[i] = new ArrayList <int[]>();
	    this.listOfSingleJunctions[i] = new ArrayList < ArrayList <int[]> > ();
	}

	// Divide groups of end-points and junction voxels
	IJ.log("Measuring trees");
	if(this.numOfTrees > 1)
	    divideVoxelsByTrees(treeIS);
	else if (this.endPointsTree.length > 0)
	{
	    //TODO bug here... 0 out of bounds (null?)
	    this.endPointsTree[0] = this.listOfEndPoints;
	    this.junctionVoxelTree[0] = this.listOfJunctionVoxels;
	}

	// Visit skeleton and measure distances.
	for(int i = 0; i < this.numOfTrees; i++)
	    visitSkeleton(taggedImage, treeIS, i+1);

	// Calculate number of junctions (skipping neighbor junction voxels)
	groupJunctions(treeIS);

	// Calculate triple points (junctions with exactly 3 branches)
	calculateTriplePoints();

	// Show results table
	showResults();

    } /* end run */

    /* -----------------------------------------------------------------------*/
    /**
     * Divide end point and junction voxels in the corresponding tree lists
     * 
     *  @param treeIS tree image
     */
    private void divideVoxelsByTrees(ImageStack treeIS) 
    {
	// Add end points to the corresponding tree
	for(int i = 0; i < this.totalNumberOfEndPoints; i++)
	{
	    final int[] p = this.listOfEndPoints.get(i);
	    this.endPointsTree[getPixel(treeIS, p) - 1].add(p);
	}

	// Add junction voxels to the corresponding tree
	for(int i = 0; i < this.totalNumberOfJunctionVoxels; i++)
	{
	    final int[] p = this.listOfJunctionVoxels.get(i);
	    this.junctionVoxelTree[getPixel(treeIS, p) - 1].add(p);
	}

    } // end divideVoxelsByTrees

    /* -----------------------------------------------------------------------*/
    /**
     * Show results table
     */
    private void showResults() 
    {
	ResultsTable rt = new ResultsTable();

	String[] head = {"Skeleton", "# Branches","# Junctions", "# End-point voxels",
		"# Junction voxels","# Slab voxels","Average Branch Length", 
		"# Triple points", "Maximum Branch Length"};

	for (int i = 0; i < head.length; i++)
	    rt.setHeading(i,head[i]);	

	for(int i = 0 ; i < this.numOfTrees; i++)
	{
	    rt.incrementCounter();

	    rt.addValue(1, this.numberOfBranches[i]);        
	    rt.addValue(2, this.numberOfJunctions[i]);
	    rt.addValue(3, this.numberOfEndPoints[i]);
	    rt.addValue(4, this.numberOfJunctionVoxels[i]);
	    rt.addValue(5, this.numberOfSlabs[i]);
	    rt.addValue(6, this.averageBranchLength[i]);
	    rt.addValue(7, this.numberOfTriplePoints[i]);
	    rt.addValue(8, this.maximumBranchLength[i]);

	    rt.show("Results");

	    IJ.log("--- Skeleton #" + (i+1) + " ---");
	    IJ.log("Coordinates of the largest branch:");
	    IJ.log("Initial point: (" + (this.initialPoint[i][0] * this.imRef.getCalibration().pixelWidth) + ", " 
		    + (this.initialPoint[i][1] * this.imRef.getCalibration().pixelHeight) + ", "
		    + (this.initialPoint[i][2] * this.imRef.getCalibration().pixelDepth) + ")" );
	    IJ.log("Final point: (" + (this.finalPoint[i][0] * this.imRef.getCalibration().pixelWidth) + ", " 
		    + (this.finalPoint[i][1] * this.imRef.getCalibration().pixelHeight) + ", "
		    + (this.finalPoint[i][2] * this.imRef.getCalibration().pixelDepth) + ")" );
	    IJ.log("Euclidean distance: " + this.calculateDistance(this.initialPoint[i], this.finalPoint[i]));
	}
    }

    /* -----------------------------------------------------------------------*/
    /**
     * Visit skeleton from end points and register measures.
     * 
     * @param taggedImage
     */
    private void visitSkeleton(ImageStack taggedImage) 
    {

	// length of branches
	double branchLength = 0;		
	int numberOfBranches = 0;
	double maximumBranchLength = 0;
	double averageBranchLength = 0;
	int[] initialPoint = null;
	int[] finalPoint = null;


	// Visit branches starting at end points
	for(int i = 0; i < this.totalNumberOfEndPoints; i++)
	{			
	    int[] endPointCoord = this.listOfEndPoints.get(i);

	    // visit branch until next junction or end point.
	    double length = visitBranch(endPointCoord);

	    if(length == 0)
		continue;

	    // increase number of branches
	    numberOfBranches++;
	    branchLength += length;				

	    // update maximum branch length
	    if(length > maximumBranchLength)
	    {
		maximumBranchLength = length;
		initialPoint = endPointCoord;
		finalPoint = this.auxPoint;
	    }
	}


	// Now visit branches starting at junctions
	for(int i = 0; i < this.totalNumberOfJunctionVoxels; i++)
	{
	    int[] junctionCoord = this.listOfJunctionVoxels.get(i);

	    // Mark junction as visited
	    setVisited(junctionCoord, true);

	    int[] nextPoint = getNextUnvisitedVoxel(junctionCoord);

	    while(nextPoint != null)
	    {
		branchLength += calculateDistance(junctionCoord, nextPoint);								

		double length = visitBranch(nextPoint);

		branchLength += length;

		// Increase number of branches
		if(length != 0)
		{
		    numberOfBranches++;
		    // update maximum branch length
		    if(length > maximumBranchLength)
		    {
			maximumBranchLength = length;
			initialPoint = junctionCoord;
			finalPoint = this.auxPoint;
		    }
		}

		nextPoint = getNextUnvisitedVoxel(junctionCoord);
	    }					
	}

	// Average length
	averageBranchLength = branchLength / numberOfBranches;

    } /* end visitSkeleton */


    /* -----------------------------------------------------------------------*/
    /**
     * Visit skeleton from end points and register measures.
     * 
     * @param taggedImage
     * @param treeImage skeleton image with tree classification
     * @param currentTree number of the tree to be visited
     */
    private void visitSkeleton(ImageStack taggedImage, ImageStack treeImage, int currentTree) 
    {

	//System.out.println(" Analyzing tree number " + currentTree);
	// length of branches
	double branchLength = 0;


	final int iTree = currentTree - 1;

	this.maximumBranchLength[iTree] = 0;
	this.numberOfEndPoints[iTree] = this.endPointsTree[iTree].size();
	this.numberOfJunctionVoxels[iTree] = this.junctionVoxelTree[iTree].size();
	this.numberOfSlabs[iTree] = 0;

	// Visit branches starting at end points
	for(int i = 0; i < this.numberOfEndPoints[iTree]; i++)
	{			
	    int[] endPointCoord = this.endPointsTree[iTree].get(i);

	    // Check tree number
	    //if(currentTree != getPixel(treeImage, endPointCoord))
	    //	continue;

	    // Increment number of end-point voxels on that tree
	    //this.numberOfEndPoints[iTree]++;

	    // else, visit branch until next junction or end point.
	    double length = visitBranch(endPointCoord, iTree);

	    if(length == 0)
		continue;

	    // increase number of branches
	    this.numberOfBranches[iTree]++;
	    branchLength += length;				

	    // update maximum branch length
	    if(length > this.maximumBranchLength[iTree])
	    {
		this.maximumBranchLength[iTree] = length;
		this.initialPoint[iTree] = endPointCoord;
		this.finalPoint[iTree] = this.auxPoint;
	    }
	}


	// Now visit branches starting at junctions
	for(int i = 0; i < this.numberOfJunctionVoxels[iTree]; i++)
	{
	    int[] junctionCoord = this.junctionVoxelTree[iTree].get(i);

	    // Check tree number
	    //if(currentTree != getPixel(treeImage, junctionCoord))
	    //	continue;
	    // Increment number of junction voxels
	    //this.numberOfJunctionVoxels[iTree]++;

	    // Mark junction as visited
	    setVisited(junctionCoord, true);

	    int[] nextPoint = getNextUnvisitedVoxel(junctionCoord);

	    while(nextPoint != null)
	    {
		branchLength += calculateDistance(junctionCoord, nextPoint);								

		double length = visitBranch(nextPoint, iTree);

		branchLength += length;

		// Increase number of branches
		if(length != 0)
		{
		    this.numberOfBranches[iTree]++;
		    // update maximum branch length
		    if(length > this.maximumBranchLength[iTree])
		    {
			this.maximumBranchLength[iTree] = length;
			this.initialPoint[iTree] = junctionCoord;
			this.finalPoint[iTree] = this.auxPoint;
		    }
		}

		nextPoint = getNextUnvisitedVoxel(junctionCoord);
	    }					
	}

	if(this.numberOfBranches[iTree] == 0)
	    return;
	// Average length
	this.averageBranchLength[iTree] = branchLength / this.numberOfBranches[iTree];

    } /* end visitSkeleton */

    /* -----------------------------------------------------------------------*/
    /**
     * Color the different trees in the skeleton.
     * 
     * @param taggedImage
     * 
     * @return image with every tree tagged with a different number 
     */
    private ImageStack markTrees(ImageStack taggedImage) 
    {
	// Create output image
	ImageStack outputImage = new ImageStack(this.width, this.height, taggedImage.getColorModel());	
	for (int z = 0; z < depth; z++)
	{
	    outputImage.addSlice(taggedImage.getSliceLabel(z+1), new ByteProcessor(this.width, this.height));	
	}

	this.numOfTrees = 0;

	byte color = 0;

	// Visit trees starting at end points
	//TODO what if trees don't have an end point!?
	for(int i = 0; i < this.totalNumberOfEndPoints; i++)
	{			
	    int[] endPointCoord = this.listOfEndPoints.get(i);

	    color++;

	    if(color == 256)
	    {
		IJ.error("More than 255 skeletons in the image. AnalyzeSkeleton can only process up to 255");
		return null;
	    }

	    // else, visit branch until next junction or end point.
	    int length = visitTree(endPointCoord, outputImage, color);

	    if(length == 0)
	    {
		color--; // the color was not used
		continue;
	    }

	    // increase number of trees			
	    this.numOfTrees++;
	}

	//System.out.println("Number of trees = " + this.numOfTrees);

	// Show tree image.
	/*
		ImagePlus treesIP = new ImagePlus("Trees skeleton", outputImage);
		treesIP.show();

		// Set same calibration as the input image
		treesIP.setCalibration(this.imRef.getCalibration());

		// We apply the Fire LUT and reset the min and max to be between 0-255.
		IJ.run("Fire");

		//IJ.resetMinAndMax();
		treesIP.resetDisplayRange();
		treesIP.updateAndDraw();
	 */
	// Reset visited variable
	this.visited = null;
	this.visited = new boolean[this.width][this.height][this.depth];

	return outputImage;

    } /* end markTrees */


    /* --------------------------------------------------------------*/
    /**
     * 
     * @param startingPoint
     * @param outputImage
     * @param color
     * @return
     */
    private int visitTree(int[] startingPoint, ImageStack outputImage,
	    byte color) 
    {
	int numOfVoxels = 0;

	//IJ.log("visiting " + pointToString(startingPoint));

	if(isVisited(startingPoint))	
	    return 0;
	// Set pixel color
	this.setPixel(outputImage, startingPoint[0], startingPoint[1], startingPoint[2], color);
	setVisited(startingPoint, true);

	ArrayList <int[]> toRevisit = new ArrayList <int []>();

	int[] nextPoint = getNextUnvisitedVoxel(startingPoint);

	while(nextPoint != null || toRevisit.size() != 0)
	{
	    if(nextPoint != null)
	    {
		if(!isVisited(nextPoint))
		{
		    numOfVoxels++;

		    //IJ.log("visiting " + pointToString(nextPoint));

		    // Set color and visit flat
		    this.setPixel(outputImage, nextPoint[0], nextPoint[1], nextPoint[2], color);
		    setVisited(nextPoint, true);

		    // If it is a junction, add it to the revisit list
		    if(isJunction(nextPoint))
			toRevisit.add(nextPoint);

		    // Calculate next point to visit
		    nextPoint = getNextUnvisitedVoxel(nextPoint);
		}				
	    }
	    else // revisit list
	    {
		nextPoint = toRevisit.get(0);
		toRevisit.remove(0);
		// Calculate next point to visit
		nextPoint = getNextUnvisitedVoxel(nextPoint);
	    }				
	}

	return numOfVoxels;
    }

    /* -----------------------------------------------------------------------*/
    /**
     * Visit a branch and calculate length
     * 
     * @param startingPoint starting coordinates
     * @return branch length
     */
    private double visitBranch(int[] startingPoint) 
    {
	double length = 0;

	// mark starting point as visited
	setVisited(startingPoint, true);

	// Get next unvisited voxel
	int[] nextPoint = getNextUnvisitedVoxel(startingPoint);

	if (nextPoint == null)
	    return 0;

	int[] previousPoint = startingPoint;

	// We visit the branch until we find an end point or a junction
	while(nextPoint != null && isSlab(nextPoint))
	{
	    // Add length
	    length += calculateDistance(previousPoint, nextPoint);

	    // Mark as visited
	    setVisited(nextPoint, true);

	    // Move in the graph
	    previousPoint = nextPoint;			
	    nextPoint = getNextUnvisitedVoxel(previousPoint);			
	}


	if(nextPoint != null)
	{
	    // Add distance to last point
	    length += calculateDistance(previousPoint, nextPoint);

	    // Mark last point as visited
	    setVisited(nextPoint, true);
	}

	this.auxPoint = previousPoint;

	return length;
    } /* end visitBranch*/

    /* -----------------------------------------------------------------------*/
    /**
     * Visit a branch and calculate length in a specifc tree
     * 
     * @param startingPoint starting coordinates
     * @param iTree tree index
     * @return branch length
     */
    private double visitBranch(int[] startingPoint, int iTree) 
    {
	double length = 0;

	// mark starting point as visited
	setVisited(startingPoint, true);

	// Get next unvisited voxel
	int[] nextPoint = getNextUnvisitedVoxel(startingPoint);

	if (nextPoint == null)
	    return 0;

	int[] previousPoint = startingPoint;

	// We visit the branch until we find an end point or a junction
	while(nextPoint != null && isSlab(nextPoint))
	{
	    this.numberOfSlabs[iTree]++;

	    // Add length
	    length += calculateDistance(previousPoint, nextPoint);

	    // Mark as visited
	    setVisited(nextPoint, true);

	    // Move in the graph
	    previousPoint = nextPoint;			
	    nextPoint = getNextUnvisitedVoxel(previousPoint);			
	}


	if(nextPoint != null)
	{
	    // Add distance to last point
	    length += calculateDistance(previousPoint, nextPoint);

	    // Mark last point as visited
	    setVisited(nextPoint, true);
	}

	this.auxPoint = previousPoint;

	return length;
    } /* end visitBranch*/	

    /* -----------------------------------------------------------------------*/
    /**
     * Calculate distance between two points in 3D
     * 
     * @param point1 first point coordinates
     * @param point2 second point coordinates
     * @return distance (in the corresponding units)
     */
    private double calculateDistance(int[] point1, int[] point2) 
    {		
	int x1 = point1[0];
	int y1 = point1[1];
	int z1 = point1[2];
	int x2 = point2[0];
	int y2 = point2[1];
	int z2 = point2[2];
	return Math.sqrt((x1 - x2) * (x1 - x2) * vW * vW 
		+ (y1 - y2) * (y1 - y2) * vH * vH
		+ (z1 - z2) * (z1 - z2) * vD * vD);
    }

    /* -----------------------------------------------------------------------*/
    /**
     * Calculate number of junction skipping neighbor junction voxels
     * 
     * @param treeIS tree stack
     */
    private void groupJunctions(ImageStack treeIS) 
    {

	for (int iTree = 0; iTree < this.numOfTrees; iTree++)
	{
	    // Visit list of junction voxels
	    for(int i = 0; i < this.numberOfJunctionVoxels[iTree]; i ++)
	    {
		int[] pi = this.junctionVoxelTree[iTree].get(i);
		boolean grouped = false;

		for(int j = 0; j < this.listOfSingleJunctions[iTree].size(); j++)
		{
		    ArrayList <int[]> groupOfJunctions = this.listOfSingleJunctions[iTree].get(j);
		    for(int k = 0; k < groupOfJunctions.size(); k++)
		    {
			int[] pk = groupOfJunctions.get(k);				

			// If two junction voxels are neighbors, we group them
			// in the same list
			if(isNeighbor(pi, pk))
			{
			    groupOfJunctions.add(pi);
			    grouped = true;
			    break;
			}

		    }

		    if(grouped)
			break;					
		}

		if(!grouped)
		{
		    ArrayList <int[]> newGroup = new ArrayList<int[]>();
		    newGroup.add(pi);
		    this.listOfSingleJunctions[iTree].add(newGroup);
		}
	    }
	}


	// Count number of single junctions for every tree in the image
	for (int iTree = 0; iTree < this.numOfTrees; iTree++)
	{
	    this.numberOfJunctions[iTree] = this.listOfSingleJunctions[iTree].size();
	}


    }	

    /* -----------------------------------------------------------------------*/
    /**
     * Calculate number of triple points in the skeleton. Triple points are
     * junctions with exactly 3 branches.
     */
    private void calculateTriplePoints() 
    {
	for (int iTree = 0; iTree < this.numOfTrees; iTree++)
	{			
	    // Visit the groups of junction voxels
	    for(int i = 0; i < this.numberOfJunctions[iTree]; i ++)
	    {

		ArrayList <int[]> groupOfJunctions = this.listOfSingleJunctions[iTree].get(i);

		// Count the number of slab neighbors of every voxel in the group
		int nSlab = 0;
		for(int j = 0; j < groupOfJunctions.size(); j++)
		{
		    int[] pj = groupOfJunctions.get(j);

		    // Get neighbors and check the slabs
		    byte[] neighborhood = this.getNeighborhood(this.taggedImage, pj[0], pj[1], pj[2]);
		    for(int k = 0; k < 27; k++)
			if (neighborhood[k] == Analyze_Skeleton.SLAB)
			    nSlab++;
		}
		// If the junction has only 3 slab neighbors, then it is a triple point
		if (nSlab == 3)	
		    this.numberOfTriplePoints[iTree] ++;

	    }		

	}

    }// end calculateTriplePoints


    /* -----------------------------------------------------------------------*/
    /**
     * Calculate if two points are neighbors
     * 
     * @param point1 first point
     * @param point2 second point
     * @return true if the points are neighbors (26-pixel neighborhood)
     */
    private boolean isNeighbor(int[] point1, int[] point2) 
    {		
	return Math.sqrt(  Math.pow( (point1[0] - point2[0]), 2) 
		+ Math.pow( (point1[1] - point2[1]), 2)
		+ Math.pow( (point1[2] - point2[2]), 2)) <= Math.sqrt(3);
    }

    /* -----------------------------------------------------------------------*/
    /**
     * Check if the point is slab
     *  
     * @param point actual point
     * @return true if the point has slab status
     */
    private boolean isSlab(int[] point) 
    {		
	return getPixel(this.taggedImage, point[0], point[1], point[2]) == Analyze_Skeleton.SLAB;
    }

    /* -----------------------------------------------------------------------*/
    /**
     * Check if the point is a junction
     *  
     * @param point actual point
     * @return true if the point has slab status
     */
    private boolean isJunction(int[] point) 
    {		
	return getPixel(this.taggedImage, point[0], point[1], point[2]) == Analyze_Skeleton.JUNCTION;
    }	

    /* -----------------------------------------------------------------------*/
    /**
     * Get next unvisited neighbor voxel 
     * 
     * @param point starting point
     * @return unvisited neighbor or null if all neighbors are visited
     */
    private int[] getNextUnvisitedVoxel(int[] point) 
    {
	int[] unvisitedNeighbor = null;

	// Check neighbors status
	for(int x = -1; x < 2; x++)
	    for(int y = -1; y < 2; y++)
		for(int z = -1; z < 2; z++)
		{
		    if(x == 0 && y == 0 && z == 0)
			continue;

		    if(getPixel(this.inputImage, point[0] + x, point[1] + y, point[2] + z) != 0
			    && isVisited(point[0] + x, point[1] + y, point[2] + z) == false)						
		    {					
			unvisitedNeighbor = new int[]{point[0] + x, point[1] + y, point[2] + z};
			break;
		    }

		}

	return unvisitedNeighbor;
    }/* end getNextUnvisitedVoxel */

    /* -----------------------------------------------------------------------*/
    /**
     * Check if a voxel is visited taking into account the borders. 
     * Out of range voxels are considered as visited. 
     * 
     * @param point
     * @return true if the voxel is visited
     */
    private boolean isVisited(int [] point) 
    {
	return isVisited(point[0], point[1], point[2]);
    }

    /* -----------------------------------------------------------------------*/
    /**
     * Check if a voxel is visited taking into account the borders. 
     * Out of range voxels are considered as visited. 
     * 
     * @param x x- voxel coordinate
     * @param y y- voxel coordinate
     * @param z z- voxel coordinate
     * @return true if the voxel is visited
     */
    private boolean isVisited(int x, int y, int z) 
    {
	if(x >= 0 && x < this.width && y >= 0 && y < this.height && z >= 0 && z < this.depth)
	    return this.visited[x][y][z];
	return true;
    }


    /* -----------------------------------------------------------------------*/
    /**
     * Set value in the visited flags matrix
     * 
     * @param x x- voxel coordinate
     * @param y y- voxel coordinate
     * @param z z- voxel coordinate
     * @param b
     */
    private void setVisited(int x, int y, int z, boolean b) 
    {
	if(x >= 0 && x < this.width && y >= 0 && y < this.height && z >= 0 && z < this.depth)
	    this.visited[x][y][z] = b;		
    }

    /* -----------------------------------------------------------------------*/
    /**
     * Set value in the visited flags matrix
     * 
     * @param point voxel coordinates
     * @param b visited flag value
     */
    private void setVisited(int[] point, boolean b) 
    {
	int x = point[0];
	int y = point[1];
	int z = point[2];

	setVisited(x, y, z, b);	
    }

    /*--------------------------------------------------------------------*/
    /**
     * Prune end branches
     * 
     * @param stack ImageStack skeleton image
     * 
     */
    private ImageStack pruneEndBranches(ImageStack stack){
	IJ.log("Started pruneEndBranches, "+this.listOfEndPoints.size()+" end points");
	while (this.listOfEndPoints.size()>1){
	    for (int i = 0; i < this.listOfEndPoints.size(); i++){
		int[] endPoint = this.listOfEndPoints.get(i);
		int x = endPoint[0], y = endPoint[1], z = endPoint[2];
		if (getNumberOfNeighbors(stack, x, y, z) == 1){
		    //remove the end voxel
		    setPixel(stack, x, y, z, (byte) 0);
		    IJ.log("Set ("+x+", "+y+", "+z+") to 0");
		    //get the values of the neighbors 
		    byte[] nHood = getNeighborhood(stack, x, y, z);
		    //get the coordinates of the single neighbor
		    for (int p = 0; p < 27; p++){
			if (nHood[p] != 0){
			    //translate the neighbourhood index 
			    //into new endpoint coordinates
			    switch(p){
			    case  0:   x -= 1; y -= 1; z -= 1; break;
			    case  1:   y -= 1; z -= 1; break;
			    case  2:   x += 1; y -= 1; z -= 1; break;
			    case  3:   x -= 1; z -= 1; break;
			    case  4:   z -= 1; break;
			    case  5:   x += 1; z -= 1; break;
			    case  6:   x -= 1; y += 1; z -= 1; break;
			    case  7:   y += 1; z -= 1; break;
			    case  8:   x += 1; y += 1; z -= 1; break;
			    case  9:   x -= 1; y -= 1; break;
			    case 10:   y -= 1; break;
			    case 11:   x += 1; y -= 1; break;
			    case 12:   x -= 1; break;
			    case 13:   break;
			    case 14:   x += 1; break;
			    case 15:   x -= 1; y += 1; break;
			    case 16:   y += 1; break;
			    case 17:   x += 1; y += 1; break;
			    case 18:   x -= 1; y -= 1; z += 1; break;
			    case 19:   y -= 1; z += 1; break;
			    case 20:   x += 1; y -= 1; z += 1; break;
			    case 21:   x -= 1; z += 1; break;
			    case 22:   z += 1; break;
			    case 23:   x += 1; z += 1; break;
			    case 24:   x -= 1; y += 1; z += 1; break;
			    case 25:   y += 1; z += 1; break;
			    case 26:   x += 1; y += 1; z += 1; break;			    
			    }
			    endPoint[0] = x;
			    endPoint[1] = y;
			    endPoint[2] = z;
			    this.listOfEndPoints.set(i, endPoint);
			    IJ.log("Moved endpoint ("+i+") to ("+x+", "+y+", "+z+")");
			    break;
			}
		    }
		} else {
		    this.listOfEndPoints.remove(i);
		    IJ.log("Removed endpoint ("+i+") at ("+x+", "+y+", "+z+")");
		    IJ.log(this.listOfEndPoints.size()+" end points remain");
		}
	    }
	}
	return stack;
    }

    /* -----------------------------------------------------------------------*/
    /**
     * Tag skeleton dividing the voxels between end points, junctions and slab,
     *  
     * @param inputImage2 skeleton image to be tagged
     * @return tagged skeleton image
     */
    private ImageStack tagImage(ImageStack inputImage2) 
    {
	// Create output image
	ImageStack outputImage = new ImageStack(this.width, this.height, inputImage2.getColorModel());

	// Tag voxels
	for (int z = 0; z < depth; z++)
	{
	    outputImage.addSlice(inputImage2.getSliceLabel(z+1), new ByteProcessor(this.width, this.height));			
	    for (int x = 0; x < width; x++) 
		for (int y = 0; y < height; y++)
		{
		    if(getPixel(inputImage2, x, y, z) != 0)
		    {
			int numOfNeighbors = getNumberOfNeighbors(inputImage2, x, y, z);
			if(numOfNeighbors < 2)
			{
			    setPixel(outputImage, x, y, z, Analyze_Skeleton.END_POINT);
			    this.totalNumberOfEndPoints++;
			    int[] endPoint = new int[]{x, y, z};
			    this.listOfEndPoints.add(endPoint);							
			}
			else if(numOfNeighbors > 2)
			{
			    setPixel(outputImage, x, y, z, Analyze_Skeleton.JUNCTION);
			    int[] junction = new int[]{x, y, z};
			    this.listOfJunctionVoxels.add(junction);	
			    this.totalNumberOfJunctionVoxels++;
			}
			else
			{
			    setPixel(outputImage, x, y, z, Analyze_Skeleton.SLAB);
			    this.totalNumberOfSlabs++;
			}
		    }					
		}
	}

	return outputImage;
    }/* end tagImage */

    /* -----------------------------------------------------------------------*/
    /**
     * Get number of neighbors of a voxel in a 3D image (0 border conditions) 
     * 
     * @param image 3D image (ImageStack)
     * @param x x- coordinate
     * @param y y- coordinate
     * @param z z- coordinate (in image stacks the indexes start at 1)
     * @return corresponding 27-pixels neighborhood (0 if out of image)
     */
    private int getNumberOfNeighbors(ImageStack image, int x, int y, int z)
    {
	int n = 0;
	byte[] neighborhood = getNeighborhood(image, x, y, z);

	for(int i = 0; i < 27; i ++)
	    if(neighborhood[i] != 0)
		n++;
	// We return n-1 because neighborhood includes the actual voxel.
	return (n-1);			
    }


    /* -----------------------------------------------------------------------*/
    /**
     * Get neighborhood of a pixel in a 3D image (0 border conditions) 
     * 
     * @param image 3D image (ImageStack)
     * @param x x- coordinate
     * @param y y- coordinate
     * @param z z- coordinate (in image stacks the indexes start at 1)
     * @return corresponding 27-pixels neighborhood (0 if out of image)
     */
    private byte[] getNeighborhood(ImageStack image, int x, int y, int z)
    {
	byte[] neighborhood = new byte[27];

	neighborhood[ 0] = getPixel(image, x-1, y-1, z-1);
	neighborhood[ 1] = getPixel(image, x  , y-1, z-1);
	neighborhood[ 2] = getPixel(image, x+1, y-1, z-1);

	neighborhood[ 3] = getPixel(image, x-1, y,   z-1);
	neighborhood[ 4] = getPixel(image, x,   y,   z-1);
	neighborhood[ 5] = getPixel(image, x+1, y,   z-1);

	neighborhood[ 6] = getPixel(image, x-1, y+1, z-1);
	neighborhood[ 7] = getPixel(image, x,   y+1, z-1);
	neighborhood[ 8] = getPixel(image, x+1, y+1, z-1);

	neighborhood[ 9] = getPixel(image, x-1, y-1, z  );
	neighborhood[10] = getPixel(image, x,   y-1, z  );
	neighborhood[11] = getPixel(image, x+1, y-1, z  );

	neighborhood[12] = getPixel(image, x-1, y,   z  );
	neighborhood[13] = getPixel(image, x,   y,   z  );
	neighborhood[14] = getPixel(image, x+1, y,   z  );

	neighborhood[15] = getPixel(image, x-1, y+1, z  );
	neighborhood[16] = getPixel(image, x,   y+1, z  );
	neighborhood[17] = getPixel(image, x+1, y+1, z  );

	neighborhood[18] = getPixel(image, x-1, y-1, z+1);
	neighborhood[19] = getPixel(image, x,   y-1, z+1);
	neighborhood[20] = getPixel(image, x+1, y-1, z+1);

	neighborhood[21] = getPixel(image, x-1, y,   z+1);
	neighborhood[22] = getPixel(image, x,   y,   z+1);
	neighborhood[23] = getPixel(image, x+1, y,   z+1);

	neighborhood[24] = getPixel(image, x-1, y+1, z+1);
	neighborhood[25] = getPixel(image, x,   y+1, z+1);
	neighborhood[26] = getPixel(image, x+1, y+1, z+1);

	return neighborhood;
    } /* end getNeighborhood */

    /* -----------------------------------------------------------------------*/
    /**
     * Get pixel in 3D image (0 border conditions) 
     * 
     * @param image 3D image
     * @param x x- coordinate
     * @param y y- coordinate
     * @param z z- coordinate (in image stacks the indexes start at 1)
     * @return corresponding pixel (0 if out of image)
     */
    private byte getPixel(ImageStack image, int x, int y, int z)
    {
	if(x >= 0 && x < this.width && y >= 0 && y < this.height && z >= 0 && z < this.depth)
	    return ((byte[]) image.getPixels(z + 1))[x + y * this.width];
	else return 0;
    } /* end getPixel */

    /* -----------------------------------------------------------------------*/
    /**
     * Get pixel in 3D image (0 border conditions) 
     * 
     * @param image 3D image
     * @param point point to be evaluated
     * @return corresponding pixel (0 if out of image)
     */
    private byte getPixel(ImageStack image, int [] point)
    {
	return getPixel(image, point[0], point[1], point[2]);
    } /* end getPixel */


    /* -----------------------------------------------------------------------*/
    /**
     * Set pixel in 3D image 
     * 
     * @param image 3D image
     * @param x x- coordinate
     * @param y y- coordinate
     * @param z z- coordinate (in image stacks the indexes start at 1)
     * @param value pixel value
     */
    private void setPixel(ImageStack image, int x, int y, int z, byte value)
    {
	if(x >= 0 && x < this.width && y >= 0 && y < this.height && z >= 0 && z < this.depth)
	    ((byte[]) image.getPixels(z + 1))[x + y * this.width] = value;
    } /* end getPixel */


    /**
     * 
     */
    String pointToString(int[] p)
    {
	return new String("(" + p[0] + ", " + p[1] + ", " + p[2] + ")");
    }

    /* -----------------------------------------------------------------------*/
    /**
     * Show plug-in information.
     * 
     */
    void showAbout() 
    {
	IJ.showMessage(
		"About AnalyzeSkeleton...",
	"This plug-in filter analyzes a 2D/3D image skeleton.\n");
    } /* end showAbout */

}
